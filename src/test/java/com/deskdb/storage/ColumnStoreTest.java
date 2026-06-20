package com.deskdb.storage;

import com.deskdb.core.Column;
import com.deskdb.core.DataType;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ColumnStoreTest {
    private PageManager pageManager;
    private ColumnStore store;
    private Path tempFile;
    
    @BeforeEach
    public void setUp() throws Exception {
        tempFile = Files.createTempFile("test_columnstore", ".deskdb");
        pageManager = new PageManager(tempFile);
        List<Column> schema = Arrays.asList(
            new Column("id", DataType.LONG),
            new Column("nombre", DataType.STRING),
            new Column("edad", DataType.INT),
            new Column("activo", DataType.BOOLEAN)
        );
        store = new ColumnStore("test_table", schema, pageManager);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        if (pageManager != null) {
            pageManager.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }
    
    @Test
    public void testInsertAndGetValue() {
        Map<String, Object> values = new HashMap<>();
        values.put("id", 1L);
        values.put("nombre", "Ana");
        values.put("edad", 30);
        values.put("activo", true);
        
        long rowId = store.insert(values);
        
        assertEquals(1L, store.getValue(rowId, "id"));
        assertEquals("Ana", store.getValue(rowId, "nombre"));
        assertEquals(30, store.getValue(rowId, "edad"));
        assertTrue((Boolean) store.getValue(rowId, "activo"));
    }
    
    @Test
    public void testMultipleInserts() {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", (long) i);
            values.put("nombre", "Usuario" + i);
            values.put("edad", 20 + (i % 50));
            values.put("activo", i % 2 == 0);
            store.insert(values);
        }
        
        assertEquals(100, store.getRowCount());
        
        // Verificar que podemos leer todas las filas
        for (long rowId = 0; rowId < 100; rowId++) {
            assertNotNull(store.getValue(rowId, "nombre"));
        }
    }
    
    @Test
    public void testGetColumnValues() {
        for (int i = 0; i < 10; i++) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", (long) i);
            values.put("nombre", "Usuario" + i);
            values.put("edad", 25);
            values.put("activo", true);
            store.insert(values);
        }
        
        List<Long> rowIds = Arrays.asList(0L, 2L, 5L, 8L);
        List<Object> nombres = store.getColumnValues("nombre", rowIds);
        
        assertEquals(4, nombres.size());
        assertEquals("Usuario0", nombres.get(0));
        assertEquals("Usuario2", nombres.get(1));
        assertEquals("Usuario5", nombres.get(2));
        assertEquals("Usuario8", nombres.get(3));
    }
    
    @Test
    public void testUpdateValue() {
        Map<String, Object> values = new HashMap<>();
        values.put("id", 1L);
        values.put("nombre", "Ana");
        values.put("edad", 30);
        store.insert(values);
        
        store.updateValue(0L, "nombre", "Ana María");
        store.updateValue(0L, "edad", 31);
        
        assertEquals("Ana María", store.getValue(0L, "nombre"));
        assertEquals(31, store.getValue(0L, "edad"));
    }
    
    @Test
    public void testDelete() {
        Map<String, Object> values = new HashMap<>();
        values.put("id", 1L);
        values.put("nombre", "Ana");
        store.insert(values);
        
        assertEquals(1, store.getRowCount());
        assertNotNull(store.getValue(0L, "nombre"));
        
        store.delete(0L);
        
        assertEquals(0, store.getRowCount());
        assertNull(store.getValue(0L, "nombre"));
    }
    
    @Test
    public void testScanColumn() {
        for (int i = 0; i < 20; i++) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", (long) i);
            values.put("nombre", "Usuario" + i);
            values.put("edad", 20 + (i % 10));
            values.put("activo", i % 3 == 0);
            store.insert(values);
        }
        
        // Escanear por activo = true (índices 0, 3, 6, 9, 12, 15, 18 = 7 elementos)
        List<Long> activos = store.scanColumn("activo", v -> v != null && ((Boolean) v));
        
        // Verificar que encontramos al menos los esperados
        assertTrue(activos.size() >= 7, "Expected at least 7 activos, got " + activos.size());
        assertTrue(activos.contains(0L));
        assertTrue(activos.contains(3L));
        assertTrue(activos.contains(18L));
    }
    
    @Test
    public void testConcurrentInserts() throws Exception {
        int threadCount = 10;
        int insertsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < insertsPerThread; i++) {
                        Map<String, Object> values = new HashMap<>();
                        values.put("id", (long) (threadId * 1000 + i));
                        values.put("nombre", "Hilo" + threadId + "_Item" + i);
                        values.put("edad", 25);
                        values.put("activo", true);
                        store.insert(values);
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(threadCount * insertsPerThread, successCount.get());
        assertEquals(threadCount * insertsPerThread, store.getRowCount());
    }
    
    @Test
    public void testConcurrentReadsAndWrites() throws Exception {
        // Insertar datos iniciales
        for (int i = 0; i < 50; i++) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", (long) i);
            values.put("nombre", "Usuario" + i);
            values.put("edad", 30);
            values.put("activo", true);
            store.insert(values);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        AtomicInteger readSuccess = new AtomicInteger(0);
        AtomicInteger writeSuccess = new AtomicInteger(0);
        
        // Hilos de lectura
        for (int r = 0; r < 2; r++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        long rowId = i % 50;
                        Object nombre = store.getValue(rowId, "nombre");
                        if (nombre != null) {
                            readSuccess.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Hilos de escritura
        for (int w = 0; w < 2; w++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        Map<String, Object> values = new HashMap<>();
                        values.put("id", 1000L + i);
                        values.put("nombre", "Nuevo" + i);
                        values.put("edad", 25);
                        values.put("activo", true);
                        store.insert(values);
                        writeSuccess.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertTrue(readSuccess.get() > 0);
        assertEquals(100, writeSuccess.get());
        assertEquals(150, store.getRowCount());
    }
}
