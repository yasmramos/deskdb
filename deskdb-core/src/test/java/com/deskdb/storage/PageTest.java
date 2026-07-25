package com.deskdb.storage;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para Page con concurrencia y persistencia.
 */
public class PageTest {
    private Path tempFile;
    
    @BeforeEach
    public void setUp() throws IOException {
        tempFile = Files.createTempFile("deskdb_page_", ".dat");
    }
    
    @AfterEach
    public void tearDown() throws IOException {
        if (tempFile != null && Files.exists(tempFile)) {
            Files.delete(tempFile);
        }
    }
    
    @Test
    public void testPageCreation() throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            
            assertEquals(0, page.getPageNumber());
            assertEquals(1, page.getVersion());
            assertTrue(page.getDataCapacity() > 0);
        }
    }
    
    @Test
    public void testReadWriteInt() throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            
            page.putInt(0, 42);
            page.flush();
            
            assertEquals(42, page.getInt(0));
        }
    }
    
    @Test
    public void testReadWriteLong() throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            long expected = 1234567890123L;
            
            page.putLong(0, expected);
            page.flush();
            
            assertEquals(expected, page.getLong(0));
        }
    }
    
    @Test
    public void testReadWriteString() throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            String expected = "Hola DeskDB!";
            
            page.putString(0, expected);
            page.flush();
            
            assertEquals(expected, page.getString(0));
        }
    }
    
    @Test
    public void testChecksumVerification() throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            page.putInt(0, 100);
            page.flush();
            
            assertTrue(page.verifyChecksum());
        }
    }
    
    @Test
    public void testConcurrentReads() throws Exception {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            page.putInt(0, 999);
            page.flush();
            
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        int value = page.getInt(0);
                        if (value == 999) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            
            assertEquals(threadCount, successCount.get(), 
                "Todas las lecturas concurrentes deben tener éxito");
        }
    }
    
    @Test
    public void testConcurrentWrites() throws Exception {
        try (var channel = java.nio.channels.FileChannel.open(tempFile, 
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            
            Page page = new Page(channel, 0);
            
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            
            for (int i = 0; i < threadCount; i++) {
                final int value = i;
                executor.submit(() -> {
                    try {
                        page.putInt(0, value);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            
            // Al menos una escritura debe haber tenido éxito
            int finalValue = page.getInt(0);
            assertTrue(finalValue >= 0 && finalValue < threadCount,
                "El valor final debe ser de una de las escrituras");
        }
    }
}
