package com.deskdb.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 */
public class BinarySerializer {

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
                // Skip inaccessible fields
                continue;
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
            
            // Map available fields in the class for quick lookup
            Map<String, Field> classFields = new HashMap<>();
            for (Field f : getSerializableFields(clazz)) {
                f.setAccessible(true);
                classFields.put(f.getName(), f);
            }

            for (int i = 0; i < fieldCount; i++) {
                // Read field name from stream
                short nameLen = dis.readShort();
                byte[] nameBytes = new byte[nameLen];
                dis.readFully(nameBytes);
                String fieldName = new String(nameBytes, StandardCharsets.UTF_8);

                // If the current class has this field, set it. Otherwise, skip the value.
                Field targetField = classFields.get(fieldName);
                if (targetField != null) {
                    Object value = readValue(dis);
                    try {
                        targetField.set(obj, value);
                    } catch (IllegalArgumentException e) {
                        // Type mismatch between stored data and current class definition.
                        // Log warning or ignore to maintain stability.
                        System.err.println("Warning: Type mismatch for field " + fieldName + " in " + clazz.getName());
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
            // Use a special marker for null (we'll use DataType.STRING.ordinal() but with a flag)
            // Actually, let's write -1 as a type marker for NULL to keep it simple
            dos.writeByte(-1); // NULL marker
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
            dos.writeByte(DataType.DECIMAL.ordinal());
            String strVal = ((BigDecimal) value).toPlainString();
            byte[] strBytes = strVal.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
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
            dos.writeByte(DataType.STRING.ordinal()); // Store UUID as string for compatibility
            String uuidStr = ((UUID) value).toString();
            byte[] strBytes = uuidStr.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        } else if (value instanceof List) {
            // For lists, we need a way to mark them. Let's use JSON ordinal as a container marker
            // or better, create a special LIST marker. For now, use -2 as LIST marker
            dos.writeByte(-2); // LIST marker
            List<?> list = (List<?>) value;
            dos.writeInt(list.size());
            for (Object item : list) {
                writeValue(dos, item);
            }
        } else {
            // Unsupported type fallback: serialize as JSON string
            dos.writeByte(DataType.JSON.ordinal());
            String strVal = value.toString();
            byte[] strBytes = strVal.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        }
    }

    private static Object readValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        
        // Handle NULL marker
        if (type == -1) {
            return null;
        }
        
        // Handle LIST marker
        if (type == -2) {
            int size = dis.readInt();
            List<Object> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(readValue(dis));
            }
            return list;
        }
        
        // Handle DataType enum ordinals
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
                int lenBd = dis.readInt();
                byte[] bdBytes = new byte[lenBd];
                dis.readFully(bdBytes);
                return new BigDecimal(new String(bdBytes, StandardCharsets.UTF_8));
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
                throw new IOException("Unknown or unsupported data type ordinal: " + type);
        }
    }

    private static void skipValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        
        // Handle NULL marker
        if (type == -1) {
            return;
        }
        
        // Handle LIST marker
        if (type == -2) {
            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                skipValue(dis);
            }
            return;
        }
        
        // Handle DataType enum ordinals
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
            case DECIMAL:
                int len = dis.readInt();
                dis.skipBytes(len);
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
                dis.skipBytes(blobLen);
                break;
            default:
                throw new IOException("Unknown or unsupported data type ordinal during skip: " + type);
        }
    }

    private static List<Field> getSerializableFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                // Exclude static, transient, synthetic
                if (!Modifier.isStatic(modifiers) && 
                    !Modifier.isTransient(modifiers) && 
                    !field.isSynthetic()) {
                    fields.add(field);
                }
            }
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}
