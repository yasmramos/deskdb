package com.deskdb.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Deserializa bytes JSON a un Map.
     */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> deserializeMap(byte[] data) {
        try {
            return objectMapper.readValue(data, java.util.Map.class);
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
