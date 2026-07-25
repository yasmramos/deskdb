package com.deskdb.util;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BufferPool optimization.
 */
class BufferPoolTest {
    
    @Test
    void testAcquireAndRelease() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 5, 10);
        
        ByteBuffer buffer = pool.acquire();
        assertNotNull(buffer);
        assertEquals(1024, buffer.capacity());
        assertEquals(0, buffer.position());
        
        pool.release(buffer);
        assertTrue(pool.getAvailableCount() >= 1);
    }
    
    @Test
    void testBufferReuse() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 2, 5);
        
        ByteBuffer buffer1 = pool.acquire();
        int initialCreated = pool.getCreatedCount();
        
        pool.release(buffer1);
        
        ByteBuffer buffer2 = pool.acquire();
        assertEquals(initialCreated, pool.getCreatedCount());
        // Buffer should be reused from pool
        assertTrue(pool.getRecycledCount() >= 1);
    }
    
    @Test
    void testPoolGrowth() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 2, 5);
        
        // Acquire more than initial pool size
        ByteBuffer[] buffers = new ByteBuffer[5];
        for (int i = 0; i < 5; i++) {
            buffers[i] = pool.acquire();
            assertNotNull(buffers[i]);
        }
        
        assertEquals(5, pool.getCreatedCount());
        
        // Release all
        for (ByteBuffer buffer : buffers) {
            pool.release(buffer);
        }
        
        assertEquals(5, pool.getAvailableCount());
    }
    
    @Test
    void testMaxPoolSize() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        BufferPool pool = new BufferPool(1024, 2, 3);
        
        // Acquire max buffers
        ByteBuffer[] buffers = new ByteBuffer[3];
        for (int i = 0; i < 3; i++) {
            buffers[i] = pool.acquire();
        }
        
        assertEquals(3, pool.getCreatedCount());
        
        // Try to acquire one more (should wait and timeout in test)
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger acquired = new AtomicInteger(0);
        
        java.util.concurrent.Future<?> future = executor.submit(() -> {
            try {
                ByteBuffer buffer = pool.acquire();
                if (buffer != null) {
                    acquired.incrementAndGet();
                    pool.release(buffer);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        // Wait a bit
        Thread.sleep(100);
        
        // Release one buffer
        pool.release(buffers[0]);
        
        // Wait for acquisition
        future.get(2, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertEquals(1, acquired.get());
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 5, 20);
        int threadCount = 10;
        int operationsPerThread = 100;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        ByteBuffer buffer = pool.acquire();
                        
                        // Use buffer
                        buffer.putInt(j);
                        
                        pool.release(buffer);
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        assertEquals(threadCount * operationsPerThread, successCount.get());
        assertTrue(pool.getHitRate() > 0.5); // Should have good hit rate
    }
    
    @Test
    void testHitRateCalculation() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 2, 5);
        
        // First acquire - no recycle yet
        ByteBuffer buffer1 = pool.acquire();
        // Hit rate might not be 0 if pool was pre-allocated
        
        pool.release(buffer1);
        
        // Second acquire - should be a recycle
        ByteBuffer buffer2 = pool.acquire();
        // Should have some hit rate after recycling
        assertTrue(pool.getRecycledCount() >= 1);
        
        pool.release(buffer2);
    }
    
    @Test
    void testClear() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 5, 10);
        
        // Acquire some buffers
        ByteBuffer[] buffers = new ByteBuffer[3];
        for (int i = 0; i < 3; i++) {
            buffers[i] = pool.acquire();
        }
        
        pool.clear();
        
        assertEquals(0, pool.getAvailableCount());
        assertEquals(0, pool.getCreatedCount());
        assertEquals(0, pool.getRecycledCount());
    }
    
    @Test
    void testNullRelease() {
        BufferPool pool = new BufferPool(1024, 2, 5);
        
        // Should not throw exception
        assertDoesNotThrow(() -> pool.release(null));
    }
    
    @Test
    void testBufferPositionReset() throws InterruptedException {
        BufferPool pool = new BufferPool(1024, 2, 5);
        
        ByteBuffer buffer = pool.acquire();
        buffer.putInt(123);
        buffer.putLong(456L);
        
        pool.release(buffer);
        
        ByteBuffer reusedBuffer = pool.acquire();
        assertEquals(0, reusedBuffer.position());
        // Buffer capacity should be the same
        assertEquals(1024, reusedBuffer.capacity());
    }
}
