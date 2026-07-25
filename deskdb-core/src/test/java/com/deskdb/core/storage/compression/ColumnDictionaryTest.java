package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ColumnDictionary encoding.
 * Validates dictionary creation, serialization, and cardinality detection.
 */
public class ColumnDictionaryTest {

    private ColumnDictionary dictionary;

    @BeforeEach
    public void setUp() {
        dictionary = new ColumnDictionary("test_column");
    }

    @Test
    public void testPutOrGetNewValue() {
        int id1 = dictionary.putOrGet("value1");
        int id2 = dictionary.putOrGet("value2");
        
        assertEquals(0, id1, "First value should have ID 0");
        assertEquals(1, id2, "Second value should have ID 1");
        assertEquals(2, dictionary.getSize());
    }

    @Test
    public void testPutOrGetExistingValue() {
        int id1 = dictionary.putOrGet("same_value");
        int id2 = dictionary.putOrGet("same_value");
        
        assertEquals(id1, id2, "Same value should return same ID");
        assertEquals(1, dictionary.getSize(), "Dictionary should have only one entry");
    }

    @Test
    public void testGetValueById() {
        dictionary.putOrGet("apple");
        dictionary.putOrGet("banana");
        dictionary.putOrGet("cherry");
        
        assertEquals("apple", dictionary.get(0));
        assertEquals("banana", dictionary.get(1));
        assertEquals("cherry", dictionary.get(2));
    }

    @Test
    public void testGetInvalidId() {
        dictionary.putOrGet("value");
        
        assertThrows(IllegalArgumentException.class, () -> {
            dictionary.get(999);
        }, "Should throw exception for invalid ID");
        
        assertThrows(IllegalArgumentException.class, () -> {
            dictionary.get(-1);
        }, "Should throw exception for negative ID");
    }

    @Test
    public void testShouldUseDictionaryWithLowCardinality() {
        // Add few unique values many times (low cardinality)
        for (int i = 0; i < 100; i++) {
            dictionary.putOrGet("repeated_value");
        }
        
        assertTrue(dictionary.shouldUseDictionary(100), 
            "Should use dictionary for low cardinality data");
    }

    @Test
    public void testShouldUseDictionaryWithHighCardinality() {
        // Add many unique values (high cardinality)
        for (int i = 0; i < 100; i++) {
            dictionary.putOrGet("unique_value_" + i);
        }
        
        assertFalse(dictionary.shouldUseDictionary(100), 
            "Should not use dictionary for high cardinality data");
    }

    @Test
    public void testShouldUseDictionaryWithSmallDataset() {
        dictionary.putOrGet("value1");
        dictionary.putOrGet("value2");
        
        assertFalse(dictionary.shouldUseDictionary(5), 
            "Should not use dictionary for small datasets (< 10 rows)");
    }

    @Test
    public void testClearDictionary() {
        dictionary.putOrGet("value1");
        dictionary.putOrGet("value2");
        assertEquals(2, dictionary.getSize());
        
        dictionary.clear();
        
        assertEquals(0, dictionary.getSize(), "Dictionary should be empty after clear");
        assertThrows(IllegalArgumentException.class, () -> {
            dictionary.get(0);
        }, "Should throw exception after clearing");
    }

    @Test
    public void testWriteToBufferAndReadFromBuffer() {
        dictionary.putOrGet("first");
        dictionary.putOrGet("second");
        dictionary.putOrGet("third");
        
        // Write to buffer
        ByteBuffer writeBuffer = ByteBuffer.allocate(1024);
        dictionary.writeToBuffer(writeBuffer);
        
        // Read from buffer
        writeBuffer.flip();
        ColumnDictionary readDict = new ColumnDictionary("test_column");
        readDict.readFromBuffer(writeBuffer);
        
        assertEquals(dictionary.getSize(), readDict.getSize(), "Dictionary sizes should match");
        assertEquals("first", readDict.get(0));
        assertEquals("second", readDict.get(1));
        assertEquals("third", readDict.get(2));
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        ColumnDictionary concurrentDict = new ColumnDictionary("concurrent_column");
        Thread[] threads = new Thread[10];
        
        // Create multiple threads adding values concurrently
        for (int t = 0; t < 10; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    concurrentDict.putOrGet("thread" + threadId + "_value" + i);
                }
            });
            threads[t].start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(1000, concurrentDict.getSize(), 
            "Should handle concurrent access correctly");
    }

    @Test
    public void testSpecialCharactersInValues() {
        String specialValue = "áéíóú ñ ü 中文 🚀";
        int id = dictionary.putOrGet(specialValue);
        
        assertEquals(specialValue, dictionary.get(id), 
            "Should handle UTF-8 special characters");
    }

    @Test
    public void testEmptyStringValue() {
        int id = dictionary.putOrGet("");
        
        assertEquals("", dictionary.get(id), "Should handle empty string values");
        assertEquals(0, id);
    }
}
