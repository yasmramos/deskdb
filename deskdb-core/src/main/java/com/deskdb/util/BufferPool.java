package com.deskdb.util;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buffer pool for memory optimization.
 * Reuses ByteBuffers to reduce GC pressure and improve performance.
 * 
 * @author yasmramos
 * @version 1.0
 */
public class BufferPool {
    
    private final Deque<ByteBuffer> bufferPool;
    private final int bufferSize;
    private final int maxPoolSize;
    private final ReentrantLock lock;
    private int createdCount;
    private int recycledCount;
    
    /**
     * Creates a new buffer pool.
     * 
     * @param bufferSize size of each buffer in bytes
     * @param initialPoolSize initial number of buffers in the pool
     * @param maxPoolSize maximum number of buffers in the pool
     */
    public BufferPool(int bufferSize, int initialPoolSize, int maxPoolSize) {
        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
        this.bufferPool = new ArrayDeque<>(initialPoolSize);
        this.lock = new ReentrantLock();
        this.createdCount = 0;
        this.recycledCount = 0;
        
        // Pre-allocate initial buffers
        for (int i = 0; i < initialPoolSize; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            bufferPool.offer(buffer);
            createdCount++;
        }
    }
    
    /**
     * Acquires a buffer from the pool.
     * If no buffer is available and pool hasn't reached max size, creates a new one.
     * If pool is at max capacity, blocks until a buffer is returned.
     * 
     * @return a ByteBuffer ready for use (cleared)
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public ByteBuffer acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            ByteBuffer buffer = bufferPool.poll();
            
            if (buffer == null) {
                if (createdCount < maxPoolSize) {
                    // Create new buffer
                    buffer = ByteBuffer.allocateDirect(bufferSize);
                    createdCount++;
                } else {
                    // Wait for a buffer to be returned
                    lock.unlock();
                    Thread.sleep(1);
                    return acquire(); // Retry
                }
            } else {
                recycledCount++;
            }
            
            buffer.clear();
            return buffer;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * Returns a buffer to the pool for reuse.
     * 
     * @param buffer the buffer to return
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        lock.lock();
        try {
            // Only return if it's one of our buffers
            if (buffer.capacity() == bufferSize && bufferPool.size() < maxPoolSize) {
                buffer.clear();
                bufferPool.offer(buffer);
            }
            // If pool is full, let GC handle it
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the current number of available buffers in the pool.
     * 
     * @return number of available buffers
     */
    public int getAvailableCount() {
        lock.lock();
        try {
            return bufferPool.size();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the total number of buffers created.
     * 
     * @return total created buffers
     */
    public int getCreatedCount() {
        lock.lock();
        try {
            return createdCount;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the number of times buffers have been recycled.
     * 
     * @return recycle count
     */
    public int getRecycledCount() {
        lock.lock();
        try {
            return recycledCount;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the pool hit rate (recycles / total acquisitions).
     * 
     * @return hit rate between 0.0 and 1.0
     */
    public double getHitRate() {
        lock.lock();
        try {
            int totalAcquisitions = recycledCount + (createdCount - bufferPool.size());
            if (totalAcquisitions == 0) {
                return 0.0;
            }
            return (double) recycledCount / totalAcquisitions;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Clears all buffers from the pool.
     */
    public void clear() {
        lock.lock();
        try {
            bufferPool.clear();
            createdCount = 0;
            recycledCount = 0;
        } finally {
            lock.unlock();
        }
    }
}
