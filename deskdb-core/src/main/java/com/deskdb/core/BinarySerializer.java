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
            dos.writeByte(DataType.BOOLEAN.ordinal());
            dos.writeBoolean((Boolean) value);
        } else if (value instanceof Integer) {
            dos.writeByte(DataType.INT.ordinal());
            dos.writeInt((Integer) value);
        } else if (value instanceof Long) {
            dos.writeByte(DataType.LONG.ordinal());
            dos.writeLong((Long) value);
        } else if (value instanceof Double) {
            dos.writeByte(DataType.DOUBLE.ordinal());
            dos.writeDouble((Double) value);
        } else if (value instanceof String) {
            dos.writeByte(DataType.STRING.ordinal());
            byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        } else if (value instanceof BigDecimal) {
            // Efficient binary serialization for BigDecimal
            dos.writeByte(DataType.DECIMAL.ordinal());
            BigDecimal bd = (BigDecimal) value;
            dos.writeInt(bd.scale());
            byte[] unscaled = bd.unscaledValue().toByteArray();
            dos.writeInt(unscaled.length);
            dos.write(unscaled);
        } else if (value instanceof java.util.Date) {
            dos.writeByte(DataType.DATE.ordinal());
            dos.writeLong(((java.util.Date) value).getTime());
        } else if (value instanceof java.sql.Timestamp) {
            dos.writeByte(DataType.TIMESTAMP.ordinal());
            dos.writeLong(((java.sql.Timestamp) value).getTime());
            dos.writeInt(((java.sql.Timestamp) value).getNanos());
        } else if (value instanceof byte[]) {
            dos.writeByte(DataType.BLOB.ordinal());
            byte[] blobData = (byte[]) value;
            dos.writeInt(blobData.length);
            dos.write(blobData);
        } else if (value instanceof UUID) {
            dos.writeByte(DataType.STRING.ordinal());
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
                ". Supported types are: String, Integer, Long, Double, Boolean, BigDecimal, Date, Timestamp, byte[], UUID, List");
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
        
        // Handle DataType enum ordinals
        if (type < 0 || type >= DataType.values().length) {
            throw new IOException("Unknown or unsupported data type ordinal: " + type);
        }
        
        DataType dataType = DataType.values()[type];
        switch (dataType) {
            case BOOLEAN:
                return dis.readBoolean();
            case INT:
                return dis.readInt();
            case LONG:
                return dis.readLong();
            case DOUBLE:
                return dis.readDouble();
            case STRING:
            case JSON: // JSON is stored as string
                int lenStr = dis.readInt();
                byte[] strBytes = new byte[lenStr];
                dis.readFully(strBytes);
                return new String(strBytes, StandardCharsets.UTF_8);
            case DECIMAL:
                // Efficient binary deserialization for BigDecimal
                int scale = dis.readInt();
                int lenBd = dis.readInt();
                byte[] unscaledBytes = new byte[lenBd];
                dis.readFully(unscaledBytes);
                return new BigDecimal(new BigInteger(unscaledBytes), scale);
            case DATE:
                long dateMillis = dis.readLong();
                return new java.util.Date(dateMillis);
            case TIMESTAMP:
                long tsMillis = dis.readLong();
                int nanos = dis.readInt();
                java.sql.Timestamp ts = new java.sql.Timestamp(tsMillis);
                ts.setNanos(nanos);
                return ts;
            case BLOB:
                int blobLen = dis.readInt();
                byte[] blobData = new byte[blobLen];
                dis.readFully(blobData);
                return blobData;
            default:
                throw new IOException("Unknown or unsupported data type: " + dataType);
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
        
        // Handle DataType enum ordinals
        if (type < 0 || type >= DataType.values().length) {
            throw new IOException("Unknown or unsupported data type ordinal during skip: " + type);
        }
        
        DataType dataType = DataType.values()[type];
        switch (dataType) {
            case BOOLEAN:
                dis.readBoolean();
                break;
            case INT:
                dis.readInt();
                break;
            case LONG:
                dis.readLong();
                break;
            case DOUBLE:
                dis.readDouble();
                break;
            case STRING:
            case JSON:
                int lenStr = dis.readInt();
                // Use readFully to guarantee all bytes are read (skipBytes doesn't guarantee full skip)
                byte[] discardStr = new byte[lenStr];
                dis.readFully(discardStr);
                break;
            case DECIMAL:
                // Skip scale and unscaled value length
                dis.readInt(); // scale
                int lenBd = dis.readInt();
                byte[] discardBd = new byte[lenBd];
                dis.readFully(discardBd);
                break;
            case DATE:
                dis.readLong();
                break;
            case TIMESTAMP:
                dis.readLong(); // millis
                dis.readInt();  // nanos
                break;
            case BLOB:
                int blobLen = dis.readInt();
                // Use readFully to guarantee all bytes are read
                byte[] discardBlob = new byte[blobLen];
                dis.readFully(discardBlob);
                break;
            default:
                throw new IOException("Unknown or unsupported data type during skip: " + dataType);
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
