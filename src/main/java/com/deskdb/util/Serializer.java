package com.deskdb.util;

import com.deskdb.core.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utilidades para serialización y deserialización de objetos.
 * Formato binario nativo sin dependencias externas.
 */
public class Serializer {
    private static final Logger logger = LoggerFactory.getLogger(Serializer.class);

    /**
     * Serializa una fila a bytes binarios.
     * Formato: [rowId(long)][columnCount(int)][columnName(String)][valueLength(int)][value(bytes)]...
     */
    public static byte[] serializeRow(Row row) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        
        out.writeLong(row.getRowId());
        Map<String, Object> values = row.getValues();
        out.writeInt(values.size());
        
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            out.writeUTF(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                out.writeByte(0); // NULL type
            } else if (value instanceof String) {
                out.writeByte(1);
                byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                out.writeInt(strBytes.length);
                out.write(strBytes);
            } else if (value instanceof Integer) {
                out.writeByte(2);
                out.writeInt((Integer) value);
            } else if (value instanceof Long) {
                out.writeByte(3);
                out.writeLong((Long) value);
            } else if (value instanceof Double) {
                out.writeByte(4);
                out.writeDouble((Double) value);
            } else if (value instanceof Boolean) {
                out.writeByte(5);
                out.writeBoolean((Boolean) value);
            } else {
                // Fallback a serialización binaria para otros tipos
                out.writeByte(6);
                byte[] objBytes = serializeObject(value);
                out.writeInt(objBytes.length);
                out.write(objBytes);
            }
        }
        
        out.close();
        return baos.toByteArray();
    }

    /**
     * Deserializa bytes binarios a una fila.
     */
    public static Row deserializeRow(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream in = new DataInputStream(bais);
        
        long rowId = in.readLong();
        Row row = new Row(rowId);
        
        int columnCount = in.readInt();
        for (int i = 0; i < columnCount; i++) {
            String columnName = in.readUTF();
            byte type = in.readByte();
            
            Object value = null;
            switch (type) {
                case 0: // NULL
                    break;
                case 1: // STRING
                    int strLen = in.readInt();
                    byte[] strBytes = new byte[strLen];
                    in.readFully(strBytes);
                    value = new String(strBytes, StandardCharsets.UTF_8);
                    break;
                case 2: // INT
                    value = in.readInt();
                    break;
                case 3: // LONG
                    value = in.readLong();
                    break;
                case 4: // DOUBLE
                    value = in.readDouble();
                    break;
                case 5: // BOOLEAN
                    value = in.readBoolean();
                    break;
                case 6: // OBJECT
                    int objLen = in.readInt();
                    byte[] objBytes = new byte[objLen];
                    in.readFully(objBytes);
                    value = deserializeObject(objBytes);
                    break;
            }
            
            row.set(columnName, value);
        }
        
        in.close();
        return row;
    }

    /**
     * Serializa un objeto genérico a bytes (fallback).
     * Usa serialización Java nativa para tipos complejos.
     */
    @SuppressWarnings("unchecked")
    private static byte[] serializeObject(Object obj) throws IOException {
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        return baos.toByteArray();
    }

    /**
     * Deserializa bytes a un objeto genérico (fallback).
     */
    @SuppressWarnings("unchecked")
    private static <T> T deserializeObject(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        T obj = (T) ois.readObject();
        ois.close();
        return obj;
    }
}
