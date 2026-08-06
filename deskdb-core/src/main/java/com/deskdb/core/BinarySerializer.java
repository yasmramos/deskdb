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

/**
 * Custom binary serializer for DeskDB ObjectStore.
 * Provides security, version tolerance, and compact storage without third-party libs.
 * 
 * Format:
 * [Field Count (int)]
 *   For each field:
 *   [Field Name Length (short)] [Field Name (bytes)]
 *   [Type Code (byte)] [Value Data]
 * 
 * Type Codes:
 * 0: NULL
 * 1: Boolean
 * 2: Integer
 * 3: Long
 * 4: Double
 * 5: String
 * 6: BigDecimal
 * 7: List/Array (Simple types only)
 */
public class BinarySerializer {

    private static final byte TYPE_NULL = 0;
    private static final byte TYPE_BOOLEAN = 1;
    private static final byte TYPE_INT = 2;
    private static final byte TYPE_LONG = 3;
    private static final byte TYPE_DOUBLE = 4;
    private static final byte TYPE_STRING = 5;
    private static final byte TYPE_BIGDECIMAL = 6;
    private static final byte TYPE_LIST = 7;

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
            dos.writeByte(TYPE_NULL);
        } else if (value instanceof Boolean) {
            dos.writeByte(TYPE_BOOLEAN);
            dos.writeBoolean((Boolean) value);
        } else if (value instanceof Integer) {
            dos.writeByte(TYPE_INT);
            dos.writeInt((Integer) value);
        } else if (value instanceof Long) {
            dos.writeByte(TYPE_LONG);
            dos.writeLong((Long) value);
        } else if (value instanceof Double) {
            dos.writeByte(TYPE_DOUBLE);
            dos.writeDouble((Double) value);
        } else if (value instanceof String) {
            dos.writeByte(TYPE_STRING);
            byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        } else if (value instanceof BigDecimal) {
            dos.writeByte(TYPE_BIGDECIMAL);
            String strVal = ((BigDecimal) value).toPlainString();
            byte[] strBytes = strVal.getBytes(StandardCharsets.UTF_8);
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
            // Unsupported type fallback: try toString or ignore
            // For strict safety, we could throw, but for now we treat as string
            dos.writeByte(TYPE_STRING);
            String strVal = value.toString();
            byte[] strBytes = strVal.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(strBytes.length);
            dos.write(strBytes);
        }
    }

    private static Object readValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        switch (type) {
            case TYPE_NULL:
                return null;
            case TYPE_BOOLEAN:
                return dis.readBoolean();
            case TYPE_INT:
                return dis.readInt();
            case TYPE_LONG:
                return dis.readLong();
            case TYPE_DOUBLE:
                return dis.readDouble();
            case TYPE_STRING:
                int lenStr = dis.readInt();
                byte[] strBytes = new byte[lenStr];
                dis.readFully(strBytes);
                return new String(strBytes, StandardCharsets.UTF_8);
            case TYPE_BIGDECIMAL:
                int lenBd = dis.readInt();
                byte[] bdBytes = new byte[lenBd];
                dis.readFully(bdBytes);
                return new BigDecimal(new String(bdBytes, StandardCharsets.UTF_8));
            case TYPE_LIST:
                int size = dis.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readValue(dis));
                }
                return list;
            default:
                throw new IOException("Unknown type code: " + type);
        }
    }

    private static void skipValue(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        switch (type) {
            case TYPE_NULL:
                break;
            case TYPE_BOOLEAN:
                dis.readBoolean();
                break;
            case TYPE_INT:
                dis.readInt();
                break;
            case TYPE_LONG:
                dis.readLong();
                break;
            case TYPE_DOUBLE:
                dis.readDouble();
                break;
            case TYPE_STRING:
            case TYPE_BIGDECIMAL:
                int len = dis.readInt();
                dis.skipBytes(len);
                break;
            case TYPE_LIST:
                int size = dis.readInt();
                for (int i = 0; i < size; i++) {
                    skipValue(dis);
                }
                break;
            default:
                throw new IOException("Unknown type code during skip: " + type);
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
