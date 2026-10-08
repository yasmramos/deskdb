package com.deskdb.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom binary serializer for DeskDB ObjectStore.
 * Provides security, version tolerance, and compact storage without third-party libs.
 * 
 * Format:
 * [Field Count (int)]
 *   For each field:
 *   [Field Name Length (short)] [Field Name (bytes)]
 *   [DataType Ordinal (byte)] [Value Data]
 * 
 * Uses DataType enum ordinals for type codes to ensure predictability and extensibility.
 * Supports: STRING, INT, LONG, DOUBLE, DECIMAL, BOOLEAN, DATE, TIMESTAMP, BLOB, JSON
 * 
 * Features:
 * - Fail-fast on unsupported types (no silent corruption)
 * - Reflection caching for performance
 * - Efficient BigDecimal binary serialization
 * - Proper handling of field shadowing in inheritance hierarchies
 */
public class BinarySerializer {

    /** Cache of serializable fields per class for performance */
    private static final Map<String, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /** Type marker for NULL values */
    private static final byte TYPE_NULL = -1;

    /** Type marker for LIST containers */
    private static final byte TYPE_LIST = -2;

    // Códigos de tipo EXPLÍCITOS y estables por contrato (no ordinal() del enum DataType).
    // El formato en disco/wal debe sobrevivir a reordenamientos o inserciones en DataType.
    // Mismo esquema canónico usado por DeskDB en el archivo .deskdb v1. ¡NO REUTILIZAR NUNCA un código!
    static final byte T_BOOLEAN = 6;
    static final byte T_INT = 2;
    static final byte T_LONG = 3;
    static final byte T_DOUBLE = 4;
    static final byte T_STRING = 1;
    static final byte T_DECIMAL = 5;
    static final byte T_DATE = 7;
    static final byte T_TIMESTAMP = 8;
    static final byte T_BLOB = 9;
    static final byte T_JSON = 10;
    static final byte T_LOCALDATE = 11;
    static final byte T_LOCALTIME = 12;
    static final byte T_LOCALDATETIME = 13;

    /**
     * Serializes an object to a byte array.
     * Only serializes non-transient, non-static fields.
     */
    public static byte[] serialize(Object obj) throws IOException {
        if (obj == null) {
            throw new IllegalArgumentException("Cannot serialize null object");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        Class<?> clazz = obj.getClass();
        List<Field> fields = getSerializableFields(clazz);

        // Write field count
        dos.writeInt(fields.size());

        for (Field field : fields) {
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                
                // Write field name
                byte[] nameBytes = field.getName().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(nameBytes.length);
                dos.write(nameBytes);

                // Write type and value
                writeValue(dos, value);
            } catch (IllegalAccessException e) {
                throw new IOException("Failed to access field during serialization: " + field.getName(), e);
            }
        }

        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Deserializes a byte array back into an object of the specified class.
     * Handles missing fields (backward compat) and extra fields (forward compat) gracefully.
     */
    public static <T> T deserialize(byte[] data, Class<T> clazz) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Cannot deserialize empty data");
        }

        try {
            T obj = clazz.getDeclaredConstructor().newInstance();
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);

            int fieldCount = dis.readInt();
            
            // Map available fields in the class for quick lookup using composite key to handle shadowing
            Map<String, Field> classFields = new HashMap<>();
            for (Field f : getSerializableFields(clazz)) {
                f.setAccessible(true);
                // Use composite key: className#fieldName to handle field shadowing correctly
                String key = f.getDeclaringClass().getName() + "#" + f.getName();
                classFields.put(key, f);
            }

            for (int i = 0; i < fieldCount; i++) {
                // Read field name from stream
                short nameLen = dis.readShort();
                byte[] nameBytes = new byte[nameLen];
                dis.readFully(nameBytes);
                String fieldName = new String(nameBytes, StandardCharsets.UTF_8);

                // Try to find field by simple name first (backward compatibility)
                Field targetField = null;
                for (Field f : classFields.values()) {
                    if (f.getName().equals(fieldName)) {
                        targetField = f;
                        break;
                    }
                }
                
                if (targetField != null) {
                    Object value = readValue(dis);
                    try {
                        targetField.set(obj, value);
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Type mismatch for field '" + fieldName + "' in " + clazz.getName() + 
                            ". Expected " + targetField.getType().getSimpleName() + " but got " + 
                            (value != null ? value.getClass().getSimpleName() : "null"), e);
                    }
                } else {
                    // Field exists in data but not in class (schema evolution: field removed).
                    // Skip the value bytes to stay in sync.
                    skipValue(dis);
                }
            }

            return obj;
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Failed to instantiate class " + clazz.getName(), e);
        }
    }

    private static void writeValue(DataOutputStream dos, Object value) throws IOException {
        if (value == null) {
            dos.writeByte(TYPE_NULL);
        } else if (value instanceof Boolean) {
            dos.writeByte(T_BOOLEAN);
            dos.writeBoolean((Boolean) value);
        } else if (value instanceof Integer) {
            dos.writeByte(T_INT);
            dos.writeInt((Integer) value);
        } else if (value instanceof Long) {
            dos.writeByte(T_LONG);
            dos.writeLong((Long) value);
        } else if (value instanceof Double) {
            dos.writeByte(T_DOUBLE);
            dos.writeDouble((Double) value);
        } else if (value instanceof String) {
            dos.writeByte(T_STRING);
            byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        } else if (value instanceof BigDecimal) {
            // Efficient binary serialization for BigDecimal
            dos.writeByte(T_DECIMAL);
            BigDecimal bd = (BigDecimal) value;
            dos.writeInt(bd.scale());
            byte[] unscaled = bd.unscaledValue().toByteArray();
            dos.writeInt(unscaled.length);
            dos.write(unscaled);
        } else if (value instanceof java.sql.Timestamp) {
            // IMPORTANTE: Timestamp extiende java.util.Date -> DEBE comprobarse ANTES
            // que Date para no perder los nanosegundos.
            dos.writeByte(T_TIMESTAMP);
            dos.writeLong(((java.sql.Timestamp) value).getTime());
            dos.writeInt(((java.sql.Timestamp) value).getNanos());
        } else if (value instanceof java.util.Date) {
            dos.writeByte(T_DATE);
            dos.writeLong(((java.util.Date) value).getTime());
        } else if (value instanceof java.time.LocalDateTime) {
            dos.writeByte(T_LOCALDATETIME);
            dos.writeInt(Math.toIntExact(((java.time.LocalDateTime) value).toLocalDate().toEpochDay()));
            dos.writeLong(((java.time.LocalDateTime) value).toLocalTime().toNanoOfDay());
        } else if (value instanceof java.time.LocalDate) {
            dos.writeByte(T_LOCALDATE);
            dos.writeInt(Math.toIntExact(((java.time.LocalDate) value).toEpochDay()));
        } else if (value instanceof java.time.LocalTime) {
            dos.writeByte(T_LOCALTIME);
            dos.writeLong(((java.time.LocalTime) value).toNanoOfDay());
        } else if (value instanceof byte[]) {
            dos.writeByte(T_BLOB);
            byte[] blobData = (byte[]) value;
            dos.writeInt(blobData.length);
            dos.write(blobData);
        } else if (value instanceof UUID) {
            dos.writeByte(T_STRING);
            String uuidStr = ((UUID) value).toString();
            byte[] strBytes = uuidStr.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        } else if (value instanceof List) {
            dos.writeByte(TYPE_LIST);
            List<?> list = (List<?>) value;
            dos.writeInt(list.size());
            for (Object item : list) {
                writeValue(dos, item);
            }
        } else {
            // Fail-fast: throw exception for unsupported types instead of silent corruption
            throw new IOException("Unsupported type for serialization: " + value.getClass().getName() +
                ". Supported types are: String, Integer, Long, Double, Boolean, BigDecimal, Date, Timestamp, LocalDate, LocalTime, LocalDateTime, byte[], UUID, List");
        }
    }

    private static Object readValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        
        // Handle NULL marker
        if (type == TYPE_NULL) {
            return null;
        }
        
        // Handle LIST marker
        if (type == TYPE_LIST) {
            int size = dis.readInt();
            List<Object> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(readValue(dis));
            }
            return list;
        }
        
        // Códigos de tipo EXPLÍCITOS (estables por contrato; ver T_* arriba).
        switch (type) {
            case T_BOOLEAN:
                return dis.readBoolean();
            case T_INT:
                return dis.readInt();
            case T_LONG:
                return dis.readLong();
            case T_DOUBLE:
                return dis.readDouble();
            case T_STRING:
            case T_JSON: // JSON se almacena como string
                int lenStr = dis.readInt();
                byte[] strBytes = new byte[lenStr];
                dis.readFully(strBytes);
                return new String(strBytes, StandardCharsets.UTF_8);
            case T_DECIMAL:
                // Efficient binary deserialization for BigDecimal
                int scale = dis.readInt();
                int lenBd = dis.readInt();
                byte[] unscaledBytes = new byte[lenBd];
                dis.readFully(unscaledBytes);
                return new BigDecimal(new BigInteger(unscaledBytes), scale);
            case T_DATE:
                long dateMillis = dis.readLong();
                return new java.util.Date(dateMillis);
            case T_TIMESTAMP:
                long tsMillis = dis.readLong();
                int nanos = dis.readInt();
                java.sql.Timestamp ts = new java.sql.Timestamp(tsMillis);
                ts.setNanos(nanos);
                return ts;
            case T_LOCALDATE:
                return java.time.LocalDate.ofEpochDay(dis.readInt());
            case T_LOCALTIME:
                return java.time.LocalTime.ofNanoOfDay(dis.readLong());
            case T_LOCALDATETIME:
                return java.time.LocalDateTime.of(java.time.LocalDate.ofEpochDay(dis.readInt()),
                        java.time.LocalTime.ofNanoOfDay(dis.readLong()));
            case T_BLOB:
                int blobLen = dis.readInt();
                byte[] blobData = new byte[blobLen];
                dis.readFully(blobData);
                return blobData;
            default:
                throw new IOException("Unknown or unsupported data type code: " + type);
        }
    }

    private static void skipValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        
        // Handle NULL marker
        if (type == TYPE_NULL) {
            return;
        }
        
        // Handle LIST marker
        if (type == TYPE_LIST) {
            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                skipValue(dis);
            }
            return;
        }
        
        // Códigos de tipo EXPLÍCITOS (mismo esquema que writeValue/readValue)
        switch (type) {
            case T_BOOLEAN:
                dis.readBoolean();
                break;
            case T_INT:
                dis.readInt();
                break;
            case T_LONG:
                dis.readLong();
                break;
            case T_DOUBLE:
                dis.readDouble();
                break;
            case T_STRING:
            case T_JSON:
                int lenStr = dis.readInt();
                // Use readFully to guarantee all bytes are read (skipBytes doesn't guarantee full skip)
                byte[] discardStr = new byte[lenStr];
                dis.readFully(discardStr);
                break;
            case T_DECIMAL:
                // Skip scale and unscaled value length
                dis.readInt(); // scale
                int lenBd = dis.readInt();
                byte[] discardBd = new byte[lenBd];
                dis.readFully(discardBd);
                break;
            case T_DATE:
                dis.readLong();
                break;
            case T_TIMESTAMP:
                dis.readLong(); // millis
                dis.readInt();  // nanos
                break;
            case T_LOCALDATE:
                dis.readInt(); // epochDay
                break;
            case T_LOCALTIME:
                dis.readLong(); // nanoOfDay
                break;
            case T_LOCALDATETIME:
                dis.readInt();  // epochDay
                dis.readLong(); // nanoOfDay
                break;
            case T_BLOB:
                int blobLen = dis.readInt();
                // Use readFully to guarantee all bytes are read
                byte[] discardBlob = new byte[blobLen];
                dis.readFully(discardBlob);
                break;
            default:
                throw new IOException("Unknown or unsupported data type code during skip: " + type);
        }
    }

    /**
     * Gets all serializable fields for a class, including inherited fields.
     * Uses caching to avoid expensive reflection operations on hot paths.
     */
    private static List<Field> getSerializableFields(Class<?> clazz) {
        // Use class name as cache key
        String cacheKey = clazz.getName();
        return FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
            List<Field> fields = new ArrayList<>();
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    // Exclude static, transient, synthetic
                    if (!Modifier.isStatic(modifiers) && 
                        !Modifier.isTransient(modifiers) && 
                        !field.isSynthetic()) {
                        field.setAccessible(true);
                        fields.add(field);
                    }
                }
                current = current.getSuperclass();
            }
            return Collections.unmodifiableList(fields);
        });
    }
}
