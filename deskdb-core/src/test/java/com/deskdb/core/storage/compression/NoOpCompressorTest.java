package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para NoOpCompressor.
 * Valida comportamiento básico, edge cases y seguridad.
 */
@DisplayName("NoOpCompressor Integration Tests")
class NoOpCompressorTest {

    private final NoOpCompressor compressor = new NoOpCompressor();

    @Test
    @DisplayName("should compress and decompress preserving original data")
    void shouldPreserveDataAfterCompressionAndDecompression() {
        // Given
        byte[] originalData = "Hello World".getBytes();

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Decompressed data should match original");
        assertEquals(originalData.length, compressed.length, "NoOp should not change size");
    }

    @Test
    @DisplayName("should return null when compressing null input")
    void shouldReturnNullForNullInput() {
        // When
        byte[] result = compressor.compress(null);

        // Then
        assertNull(result, "Compressing null should return null");
    }

    @Test
    @DisplayName("should return null when decompressing null input")
    void shouldReturnNullForNullDecompressInput() {
        // When
        byte[] result = compressor.decompress(null);

        // Then
        assertNull(result, "Decompressing null should return null");
    }

    @Test
    @DisplayName("should handle empty array correctly")
    void shouldHandleEmptyArray() {
        // Given
        byte[] emptyData = new byte[0];

        // When
        byte[] compressed = compressor.compress(emptyData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(emptyData, decompressed, "Empty array should be preserved");
        assertEquals(0, compressed.length, "Compressed empty array should have length 0");
    }

    @Test
    @DisplayName("should handle large data efficiently")
    void shouldHandleLargeData() {
        // Given
        byte[] largeData = new byte[10000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        // When
        byte[] compressed = compressor.compress(largeData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(largeData, decompressed, "Large data should be preserved");
        assertEquals(largeData.length, compressed.length, "NoOp should maintain original size");
    }

    @Test
    @DisplayName("should return correct name identifier")
    void shouldReturnCorrectName() {
        // When
        String name = compressor.getName();

        // Then
        assertEquals("NONE", name, "Name should be 'NONE'");
    }

    @Test
    @DisplayName("should create independent copy on compression")
    void shouldCreateIndependentCopyOnCompression() {
        // Given
        byte[] originalData = new byte[]{1, 2, 3, 4, 5};

        // When
        byte[] compressed = compressor.compress(originalData);
        originalData[0] = 99; // Modify original

        // Then
        assertEquals(1, compressed[0], "Compressed data should not be affected by original modification");
    }

    @Test
    @DisplayName("should create independent copy on decompression")
    void shouldCreateIndependentCopyOnDecompression() {
        // Given
        byte[] originalData = new byte[]{1, 2, 3, 4, 5};
        byte[] compressed = compressor.compress(originalData);

        // When
        byte[] decompressed = compressor.decompress(compressed);
        compressed[0] = 99; // Modify compressed

        // Then
        assertEquals(1, decompressed[0], "Decompressed data should not be affected by compressed modification");
    }

    @Test
    @DisplayName("should handle binary data with all byte values")
    void shouldHandleAllByteValues() {
        // Given
        byte[] allValues = new byte[256];
        for (int i = 0; i < 256; i++) {
            allValues[i] = (byte) i;
        }

        // When
        byte[] compressed = compressor.compress(allValues);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(allValues, decompressed, "All byte values should be preserved");
    }

    @Test
    @DisplayName("should handle repeated compression-decompression cycles")
    void shouldHandleMultipleCycles() {
        // Given
        byte[] originalData = "TestData".getBytes();
        byte[] current = originalData;

        // When - Apply 10 cycles
        for (int i = 0; i < 10; i++) {
            current = compressor.decompress(compressor.compress(current));
        }

        // Then
        assertArrayEquals(originalData, current, "Data should remain intact after multiple cycles");
    }
}
