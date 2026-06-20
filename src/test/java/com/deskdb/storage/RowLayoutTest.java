package com.deskdb.storage;

import com.deskdb.core.Column;
import org.junit.jupiter.api.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para RowLayout.
 */
public class RowLayoutTest {
    
    private List<Column> schema;
    private RowLayout layout;
    
    @BeforeEach
    public void setUp() {
        schema = Arrays.asList(
            new Column("id", com.deskdb.core.DataType.LONG),
            new Column("nombre", com.deskdb.core.DataType.STRING),
            new Column("edad", com.deskdb.core.DataType.INT),
            new Column("activo", com.deskdb.core.DataType.BOOLEAN),
            new Column("saldo", com.deskdb.core.DataType.DOUBLE)
        );
        layout = new RowLayout(schema);
    }
    
    @Test
    public void testSerializeAndDeserialize() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        long rowId = 123L;
        int version = 1;
        long timestamp = System.currentTimeMillis();
        Object[] values = {
            123L,
            "Ana García",
            30,
            true,
            1500.50
        };
        
        // Serializar
        int bytesWritten = layout.serialize(buffer, rowId, version, timestamp, values);
        assertTrue(bytesWritten > 0);
        
        // Deserializar
        buffer.flip();
        Object[] readValues = layout.deserialize(buffer, rowId);
        
        // Verificar
        assertEquals(values.length, readValues.length);
        assertEquals(values[0], readValues[0]); // id
        assertEquals(values[1], readValues[1]); // nombre
        assertEquals(values[2], readValues[2]); // edad
        assertEquals(values[3], readValues[3]); // activo
        assertEquals(values[4], readValues[4]); // saldo
    }
    
    @Test
    public void testReadSingleColumn() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        long rowId = 456L;
        int version = 1;
        long timestamp = System.currentTimeMillis();
        Object[] values = {
            456L,
            "Luis Pérez",
            25,
            false,
            2000.75
        };
        
        layout.serialize(buffer, rowId, version, timestamp, values);
        buffer.flip();
        
        // Leer solo la columna "nombre"
        Object nombre = layout.readColumn(buffer, rowId, "nombre");
        assertEquals("Luis Pérez", nombre);
        
        // Leer solo la columna "edad"
        buffer.rewind();
        Object edad = layout.readColumn(buffer, rowId, "edad");
        assertEquals(25, edad);
        
        // Leer solo la columna "saldo"
        buffer.rewind();
        Object saldo = layout.readColumn(buffer, rowId, "saldo");
        assertEquals(2000.75, saldo);
    }
    
    @Test
    public void testNullValues() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        long rowId = 789L;
        int version = 1;
        long timestamp = System.currentTimeMillis();
        Object[] values = {
            789L,
            null, // nombre nulo
            35,
            null, // activo nulo
            3000.00
        };
        
        layout.serialize(buffer, rowId, version, timestamp, values);
        buffer.flip();
        
        Object[] readValues = layout.deserialize(buffer, rowId);
        
        assertNull(readValues[1]); // nombre
        assertNotNull(readValues[2]); // edad
        assertNull(readValues[3]); // activo
        assertNotNull(readValues[4]); // saldo
    }
    
    @Test
    public void testGetColumnIndex() {
        assertEquals(0, layout.getColumnIndex("id"));
        assertEquals(1, layout.getColumnIndex("nombre"));
        assertEquals(2, layout.getColumnIndex("edad"));
        assertEquals(3, layout.getColumnIndex("activo"));
        assertEquals(4, layout.getColumnIndex("saldo"));
        
        assertThrows(IllegalArgumentException.class, () -> {
            layout.getColumnIndex("columna_inexistente");
        });
    }
    
    @Test
    public void testGetSchema() {
        List<Column> retrievedSchema = layout.getSchema();
        assertEquals(schema.size(), retrievedSchema.size());
        assertEquals("id", retrievedSchema.get(0).getName());
        assertEquals("nombre", retrievedSchema.get(1).getName());
    }
    
    @Test
    public void testCalculateMaxRowSize() {
        int maxSize = layout.calculateMaxRowSize();
        assertTrue(maxSize > 0);
        // El tamaño máximo incluye HEADER + offsets + tipos de longitud variable (MAX_VALUE)
        // Por lo tanto, para este test solo verificamos que sea positivo y mayor que el header
        // Nota: Para tipos variables como STRING, getMaxSize() retorna Integer.MAX_VALUE
        // así que el cálculo será muy grande. Solo verificamos que sea razonable.
        assertTrue(maxSize >= RowLayout.HEADER_SIZE, 
            "Expected maxSize >= " + RowLayout.HEADER_SIZE + ", got " + maxSize);
    }
    
    @Test
    public void testMultipleRows() {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        
        // Serializar múltiples filas y guardar sus offsets
        int[] offsets = new int[10];
        for (int i = 1; i <= 10; i++) {
            offsets[i-1] = buffer.position();
            Object[] values = {
                (long) i,
                "Usuario" + i,
                20 + i,
                i % 2 == 0,
                1000.0 + i * 10.5
            };
            
            layout.serialize(buffer, i, 1, System.currentTimeMillis(), values);
        }
        
        // Leer cada fila usando su offset
        for (int i = 1; i <= 10; i++) {
            buffer.position(offsets[i-1]);
            Object[] values = layout.deserialize(buffer, i);
            assertEquals((long) i, values[0]); // Long
            assertEquals("Usuario" + i, values[1]);
            assertEquals(20 + i, values[2]);
            assertEquals(i % 2 == 0, values[3]);
            assertEquals(1000.0 + i * 10.5, values[4]);
        }
    }
    
    @Test
    public void testLargeString() {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        
        String largeString = "A".repeat(5000);
        Object[] values = {
            999L,
            largeString,
            40,
            true,
            5000.00
        };
        
        layout.serialize(buffer, 999L, 1, System.currentTimeMillis(), values);
        buffer.flip();
        
        Object[] readValues = layout.deserialize(buffer, 999L);
        assertEquals(largeString, readValues[1]);
    }
}
