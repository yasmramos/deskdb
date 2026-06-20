package com.deskdb.storage;

import org.junit.jupiter.api.*;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para PrimitiveSerializer.
 */
public class PrimitiveSerializerTest {
    
    @Test
    public void testVarIntRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        
        int[] testValues = {0, 1, 127, 128, 255, 256, 16383, 16384, Integer.MAX_VALUE};
        
        for (int value : testValues) {
            buffer.clear();
            PrimitiveSerializer.writeVarInt(buffer, value);
            buffer.flip();
            int readValue = PrimitiveSerializer.readVarInt(buffer);
            assertEquals(value, readValue, "Falló para valor: " + value);
        }
    }
    
    @Test
    public void testVarLongRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        
        long[] testValues = {0L, 1L, 127L, 128L, 255L, 256L, Long.MAX_VALUE};
        
        for (long value : testValues) {
            buffer.clear();
            PrimitiveSerializer.writeVarLong(buffer, value);
            buffer.flip();
            long readValue = PrimitiveSerializer.readVarLong(buffer);
            assertEquals(value, readValue, "Falló para valor: " + value);
        }
    }
    
    @Test
    public void testStringRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(1000);
        
        String[] testValues = {"", "Hola", "DeskDB", "Prueba con espacios", "Ñoño", null};
        
        for (String value : testValues) {
            buffer.clear();
            PrimitiveSerializer.writeString(buffer, value);
            buffer.flip();
            String readValue = PrimitiveSerializer.readString(buffer);
            assertEquals(value, readValue, "Falló para string: " + value);
        }
    }
    
    @Test
    public void testBooleanRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(10);
        
        buffer.clear();
        PrimitiveSerializer.writeBoolean(buffer, true);
        PrimitiveSerializer.writeBoolean(buffer, false);
        buffer.flip();
        
        assertTrue(PrimitiveSerializer.readBoolean(buffer));
        assertFalse(PrimitiveSerializer.readBoolean(buffer));
    }
    
    @Test
    public void testDoubleRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        
        double[] testValues = {0.0, 1.0, -1.0, 3.14159, Double.MAX_VALUE, Double.MIN_VALUE};
        
        for (double value : testValues) {
            buffer.clear();
            PrimitiveSerializer.writeDouble(buffer, value);
            buffer.flip();
            double readValue = PrimitiveSerializer.readDouble(buffer);
            assertEquals(value, readValue, 0.000001, "Falló para double: " + value);
        }
    }
    
    @Test
    public void testBytesRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(1000);
        
        byte[][] testValues = {
            new byte[]{},
            new byte[]{1, 2, 3},
            new byte[100]
        };
        
        for (byte[] value : testValues) {
            buffer.clear();
            PrimitiveSerializer.writeBytes(buffer, value);
            buffer.flip();
            byte[] readValue = PrimitiveSerializer.readBytes(buffer);
            assertArrayEquals(value, readValue, "Falló para array de bytes");
        }
    }
    
    @Test
    public void testDateRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        
        long now = System.currentTimeMillis();
        
        buffer.clear();
        PrimitiveSerializer.writeDate(buffer, now);
        buffer.flip();
        long readValue = PrimitiveSerializer.readDate(buffer);
        
        assertEquals(now, readValue);
    }
    
    @Test
    public void testEstimateVarIntSize() {
        assertEquals(1, PrimitiveSerializer.estimateVarIntSize(0));
        assertEquals(1, PrimitiveSerializer.estimateVarIntSize(127));
        assertEquals(2, PrimitiveSerializer.estimateVarIntSize(128));
        assertEquals(2, PrimitiveSerializer.estimateVarIntSize(16383));
        assertEquals(3, PrimitiveSerializer.estimateVarIntSize(16384));
        assertEquals(5, PrimitiveSerializer.estimateVarIntSize(Integer.MAX_VALUE));
    }
    
    @Test
    public void testEstimateStringSize() {
        assertEquals(1, PrimitiveSerializer.estimateStringSize(null));
        assertEquals(1, PrimitiveSerializer.estimateStringSize("")); // 0 length + 0 bytes
        assertTrue(PrimitiveSerializer.estimateStringSize("Hola") > 0);
    }
}
