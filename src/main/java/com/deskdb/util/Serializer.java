package com.deskdb.util;

import com.deskdb.core.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utilidades para serialización y deserialización de objetos.
 */
public class Serializer {
    private static final Logger logger = LoggerFactory.getLogger(Serializer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serializa un objeto a bytes JSON.
     */
    public static byte[] serialize(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            logger.error("Error al serializar objeto", e);
            throw new RuntimeException("Error al serializar objeto", e);
        }
    }

    /**
     * Deserializa bytes JSON a un objeto.
     */
    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (Exception e) {
            logger.error("Error al deserializar bytes", e);
            throw new RuntimeException("Error al deserializar bytes", e);
        }
    }

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
                // Fallback a JSON para otros tipos
                out.writeByte(6);
                String json = toJson(value);
                byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
                out.writeInt(jsonBytes.length);
                out.write(jsonBytes);
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
                case 6: // JSON/OBJECT
                    int jsonLen = in.readInt();
                    byte[] jsonBytes = new byte[jsonLen];
                    in.readFully(jsonBytes);
                    String json = new String(jsonBytes, StandardCharsets.UTF_8);
                    value = fromJson(json, Object.class);
                    break;
            }
            
            row.set(columnName, value);
        }
        
        in.close();
        return row;
    }

    /**
     * Deserializa bytes JSON a un Map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deserializeMap(byte[] data) {
        try {
            return objectMapper.readValue(data, Map.class);
        } catch (Exception e) {
            logger.error("Error al deserializar bytes a Map", e);
            throw new RuntimeException("Error al deserializar bytes a Map", e);
        }
    }

    /**
     * Convierte un objeto a string JSON.
     */
    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("Error al convertir objeto a JSON", e);
            throw new RuntimeException("Error al convertir objeto a JSON", e);
        }
    }

    /**
     * Parsea un string JSON a un objeto.
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            logger.error("Error al parsear JSON", e);
            throw new RuntimeException("Error al parsear JSON", e);
        }
    }
}
