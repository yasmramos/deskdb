package com.deskdb.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Serializadores primitivos optimizados sin reflexión.
 * Zero overhead, thread-safe, formato binario compacto.
 */
public final class PrimitiveSerializer {
    
    private PrimitiveSerializer() {
        // Utility class
    }
    
    /**
     * Escribe un entero con codificación variable (VarInt).
     * Usa 1-5 bytes dependiendo del valor.
     */
    public static int writeVarInt(ByteBuffer buffer, int value) {
        int bytesWritten = 0;
        while ((value & ~0x7F) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
            bytesWritten++;
        }
        buffer.put((byte) value);
        bytesWritten++;
        return bytesWritten;
    }
    
    /**
     * Lee un entero con codificación variable.
     */
    public static int readVarInt(ByteBuffer buffer) {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            b = buffer.get();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
    
    /**
     * Escribe un long con codificación variable (VarLong).
     * Usa 1-9 bytes dependiendo del valor.
     */
    public static int writeVarLong(ByteBuffer buffer, long value) {
        int bytesWritten = 0;
        while ((value & ~0x7FL) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
            bytesWritten++;
        }
        buffer.put((byte) value);
        bytesWritten++;
        return bytesWritten;
    }
    
    /**
     * Lee un long con codificación variable.
     */
    public static long readVarLong(ByteBuffer buffer) {
        long result = 0;
        int shift = 0;
        byte b;
        do {
            b = buffer.get();
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
    
    /**
     * Escribe un string UTF-8 con longitud variable.
     * Formato: [length:VarInt][bytes:UTF-8]
     */
    public static int writeString(ByteBuffer buffer, String value) {
        if (value == null) {
            buffer.put((byte) 0xFF); // Marker null
            return 1;
        }
        
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int lenBytes = writeVarInt(buffer, bytes.length);
        buffer.put(bytes);
        return lenBytes + bytes.length;
    }
    
    /**
     * Lee un string UTF-8 con longitud variable.
     */
    public static String readString(ByteBuffer buffer) {
        byte marker = buffer.get();
        if (marker == (byte) 0xFF) {
            return null;
        }
        
        // Retroceder un byte para leer la longitud correctamente
        buffer.position(buffer.position() - 1);
        int length = readVarInt(buffer);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Escribe un array de bytes con longitud variable.
     */
    public static int writeBytes(ByteBuffer buffer, byte[] value) {
        if (value == null) {
            writeVarInt(buffer, -1);
            return 1;
        }
        writeVarInt(buffer, value.length);
        buffer.put(value);
        return 4 + value.length; // Aproximado
    }
    
    /**
     * Lee un array de bytes con longitud variable.
     */
    public static byte[] readBytes(ByteBuffer buffer) {
        int length = readVarInt(buffer);
        if (length == -1) {
            return null;
        }
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }
    
    /**
     * Escribe un boolean como un solo byte.
     */
    public static void writeBoolean(ByteBuffer buffer, boolean value) {
        buffer.put(value ? (byte) 1 : (byte) 0);
    }
    
    /**
     * Lee un boolean desde un byte.
     */
    public static boolean readBoolean(ByteBuffer buffer) {
        return buffer.get() != 0;
    }
    
    /**
     * Escribe un double (8 bytes, IEEE 754).
     */
    public static void writeDouble(ByteBuffer buffer, double value) {
        buffer.putDouble(value);
    }
    
    /**
     * Lee un double (8 bytes, IEEE 754).
     */
    public static double readDouble(ByteBuffer buffer) {
        return buffer.getDouble();
    }
    
    /**
     * Escribe un float (4 bytes, IEEE 754).
     */
    public static void writeFloat(ByteBuffer buffer, float value) {
        buffer.putFloat(value);
    }
    
    /**
     * Lee un float (4 bytes, IEEE 754).
     */
    public static float readFloat(ByteBuffer buffer) {
        return buffer.getFloat();
    }
    
    /**
     * Escribe una fecha como epoch millis (8 bytes).
     */
    public static void writeDate(ByteBuffer buffer, long epochMillis) {
        buffer.putLong(epochMillis);
    }
    
    /**
     * Lee una fecha como epoch millis (8 bytes).
     */
    public static long readDate(ByteBuffer buffer) {
        return buffer.getLong();
    }
    
    /**
     * Calcula el tamaño estimado de un string serializado.
     */
    public static int estimateStringSize(String value) {
        if (value == null) return 1;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return estimateVarIntSize(bytes.length) + bytes.length;
    }
    
    /**
     * Calcula el tamaño de un VarInt para un valor dado.
     */
    public static int estimateVarIntSize(int value) {
        if (value < 0) return 5; // Tratado como unsigned
        if (value < (1 << 7)) return 1;
        if (value < (1 << 14)) return 2;
        if (value < (1 << 21)) return 3;
        if (value < (1 << 28)) return 4;
        return 5;
    }
}
