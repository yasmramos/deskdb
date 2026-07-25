package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ColumnCompressor interface implementations.
 * Validates compression/decompression cycles and data integrity.
 */
public class ColumnCompressorTest {

    private ColumnCompressor[] compressors;

    @BeforeEach
    public void setUp() {
        compressors = new ColumnCompressor[]{
            new NoOpCompressor(),
            new RLECompressor(),
            new DeltaCompressor()
        };
    }

    @Test
    public void testNoOpCompressorIdentity() {
        ColumnCompressor compressor = new NoOpCompressor();
        byte[] originalData = "Hello World".getBytes();
        
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);
        
        assertArrayEquals(originalData, decompressed, "NoOp compressor should return identical data");
        assertEquals("NONE", compressor.getName());
    }

    @Test
    public void testRLECompressorWithRepeatedValues() {
        ColumnCompressor compressor = new RLECompressor();
        // Create data with repeated bytes: AAAA BBBB CCCC
        byte[] originalData = new byte[] {65, 65, 65, 65, 66, 66, 66, 66, 67, 67, 67, 67};
        
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);
        
        assertArrayEquals(originalData, decompressed, "RLE decompression should restore original data");
        assertTrue(compressed.length < originalData.length, "RLE should compress repeated data");
        assertEquals("RLE", compressor.getName());
    }

    @Test
    public void testRLECompressorWithRandomData() {
        ColumnCompressor compressor = new RLECompressor();
        // Create random data with no repetitions
        byte[] originalData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);
        
        assertArrayEquals(originalData, decompressed, "RLE should preserve data integrity");
        // RLE might expand random data slightly due to overhead
        assertNotNull(compressed);
    }

    @Test
    public void testDeltaCompressorWithIncrementalValues() {
        ColumnCompressor compressor = new DeltaCompressor();
        // Create incremental data: 10, 20, 30, 40, 50
        byte[] originalData = new byte[] {10, 20, 30, 40, 50};
        
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);
        
        assertArrayEquals(originalData, decompressed, "Delta decompression should restore original data");
        assertEquals("DELTA", compressor.getName());
    }

    @Test
    public void testDeltaCompressorWithConstantValues() {
        ColumnCompressor compressor = new DeltaCompressor();
        // Create constant data: 5, 5, 5, 5, 5
        byte[] originalData = new byte[] {5, 5, 5, 5, 5};
        
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);
        
        assertArrayEquals(originalData, decompressed, "Delta should handle constant values");
    }

    @Test
    public void testEmptyDataCompression() {
        for (ColumnCompressor compressor : compressors) {
            byte[] emptyData = new byte[0];
            
            byte[] compressed = compressor.compress(emptyData);
            byte[] decompressed = compressor.decompress(compressed);
            
            assertArrayEquals(emptyData, decompressed, 
                compressor.getName() + " should handle empty data");
        }
    }

    @Test
    public void testLargeDataCompression() {
        ColumnCompressor compressor = new RLECompressor();
        // Create large dataset with patterns
        byte[] originalData = new byte[1000];
        for (int i = 0; i < originalData.length; i++) {
            originalData[i] = (byte) (i % 256);
        }
        
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);
        
        assertArrayEquals(originalData, decompressed, "Should handle large datasets");
        assertNotNull(compressed);
    }

    @Test
    public void testNullSafety() {
        for (ColumnCompressor compressor : compressors) {
            // Different compressors handle null differently based on implementation
            if (compressor instanceof NoOpCompressor) {
                // NoOpCompressor returns null for null input
                assertNull(compressor.compress(null), "NoOpCompressor should return null for null input");
                assertNull(compressor.decompress(null), "NoOpCompressor should return null for null input");
            } else if (compressor instanceof RLECompressor || compressor instanceof DeltaCompressor) {
                // RLE and Delta return empty array for null/empty input
                assertArrayEquals(new byte[0], compressor.compress(null), 
                    compressor.getName() + " should return empty array for null input");
                assertArrayEquals(new byte[0], compressor.decompress(null), 
                    compressor.getName() + " should return empty array for null input");
            }
        }
    }

    @Test
    public void testMultipleCompressionCycles() {
        ColumnCompressor compressor = new RLECompressor();
        byte[] originalData = "TestData".getBytes();
        
        // Compress and decompress multiple times
        byte[] data = originalData;
        for (int i = 0; i < 5; i++) {
            byte[] compressed = compressor.compress(data);
            data = compressor.decompress(compressed);
        }
        
        assertArrayEquals(originalData, data, "Multiple cycles should preserve data");
    }
}
