package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;

public class ColumnDictionaryTest {

    @Test
    public void testPutAndGet() {
        ColumnDictionary dict = new ColumnDictionary("status");
        
        int id1 = dict.putOrGet("active");
        int id2 = dict.putOrGet("inactive");
        int id3 = dict.putOrGet("active"); // Should return same ID as id1
        
        assertEquals(0, id1);
        assertEquals(1, id2);
        assertEquals(0, id3); // Duplicate should return existing ID
        
        assertEquals("active", dict.get(id1));
        assertEquals("inactive", dict.get(id2));
        assertEquals("active", dict.get(id3));
    }

    @Test
    public void testDictionarySize() {
        ColumnDictionary dict = new ColumnDictionary("category");
        
        dict.putOrGet("A");
        dict.putOrGet("B");
        dict.putOrGet("C");
        dict.putOrGet("A"); // Duplicate
        dict.putOrGet("B"); // Duplicate
        
        assertEquals(3, dict.getSize());
    }

    @Test
    public void testShouldUseDictionary() {
        ColumnDictionary dict = new ColumnDictionary("low_cardinality");
        
        // Add many duplicates
        for (int i = 0; i < 100; i++) {
            dict.putOrGet(i % 5 == 0 ? "A" : "B"); // Only 2 unique values
        }
        
        assertTrue(dict.shouldUseDictionary(100)); // Should be true (2% cardinality)
        
        // Add high cardinality
        ColumnDictionary dict2 = new ColumnDictionary("high_cardinality");
        for (int i = 0; i < 100; i++) {
            dict2.putOrGet("value_" + i); // 100 unique values
        }
        
        assertFalse(dict2.shouldUseDictionary(100)); // Should be false (100% cardinality)
    }

    @Test
    public void testWriteAndReadFromBuffer() {
        ColumnDictionary dict = new ColumnDictionary("test_column");
        
        dict.putOrGet("apple");
        dict.putOrGet("banana");
        dict.putOrGet("cherry");
        
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        dict.writeToBuffer(buffer);
        
        buffer.flip(); // Prepare for reading
        
        ColumnDictionary dict2 = new ColumnDictionary("test_column");
        dict2.readFromBuffer(buffer);
        
        assertEquals(3, dict2.getSize());
        assertEquals("apple", dict2.get(0));
        assertEquals("banana", dict2.get(1));
        assertEquals("cherry", dict2.get(2));
    }

    @Test
    public void testClear() {
        ColumnDictionary dict = new ColumnDictionary("temp");
        
        dict.putOrGet("x");
        dict.putOrGet("y");
        assertEquals(2, dict.getSize());
        
        dict.clear();
        assertEquals(0, dict.getSize());
        
        // Should be able to reuse
        int newId = dict.putOrGet("z");
        assertEquals(0, newId);
        assertEquals(1, dict.getSize());
    }

    @Test
    public void testInvalidId() {
        ColumnDictionary dict = new ColumnDictionary("error_test");
        dict.putOrGet("valid");
        
        assertThrows(IllegalArgumentException.class, () -> {
            dict.get(-1);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            dict.get(100);
        });
    }
}
