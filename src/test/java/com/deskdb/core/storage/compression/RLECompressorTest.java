package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para RLECompressor.
 * Valida compresión Run-Length Encoding, edge cases y rendimiento.
 */
@DisplayName("RLECompressor Integration Tests")
class RLECompressorTest {

    private final RLECompressor compressor = new RLECompressor();

    @Test
    @DisplayName("should compress and decompress preserving original data")
    void shouldPreserveDataAfterCompressionAndDecompression() {
        // Given - Data with repetitions (ideal for RLE)
        byte[] originalData = new byte[]{1, 1, 1, 1, 2, 2, 3, 3, 3};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Decompressed data should match original");
        assertTrue(compressed.length < originalData.length, "RLE should reduce size for repetitive data");
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
    @DisplayName("should handle null input gracefully")
    void shouldHandleNullInput() {
        // When
        byte[] compressed = compressor.compress(null);
        byte[] decompressed = compressor.decompress(null);

        // Then
        assertArrayEquals(new byte[0], compressed, "Null should produce empty array");
        assertArrayEquals(new byte[0], decompressed, "Null decompress should produce empty array");
    }

    @Test
    @DisplayName("should compress single value repeated many times efficiently")
    void shouldCompressHighlyRepetitiveData() {
        // Given - 255 repetitions of the same value (max count per RLE block)
        byte[] originalData = new byte[255];
        for (int i = 0; i < 255; i++) {
            originalData[i] = 42;
        }

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Highly repetitive data should be preserved");
        assertEquals(2, compressed.length, "255 repetitions should compress to [255][value] = 2 bytes");
    }

    @Test
    @DisplayName("should handle more than 255 repetitions correctly")
    void shouldHandleMoreThanMaxRepetitions() {
        // Given - 300 repetitions (exceeds max count of 255)
        byte[] originalData = new byte[300];
        for (int i = 0; i < 300; i++) {
            originalData[i] = 7;
        }

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Data exceeding max repetitions should be preserved");
        assertEquals(4, compressed.length, "300 repetitions should need 2 blocks: [255][7][45][7] = 4 bytes");
    }

    @Test
    @DisplayName("should handle non-repetitive data correctly")
    void shouldHandleNonRepetitiveData() {
        // Given - All different values
        byte[] originalData = new byte[10];
        for (int i = 0; i < 10; i++) {
            originalData[i] = (byte) i;
        }

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Non-repetitive data should be preserved");
        assertEquals(originalData.length * 2, compressed.length, "Each value needs [1][value] = 2 bytes");
    }

    @Test
    @DisplayName("should return correct name identifier")
    void shouldReturnCorrectName() {
        // When
        String name = compressor.getName();

        // Then
        assertEquals("RLE", name, "Name should be 'RLE'");
    }

    @Test
    @DisplayName("should handle alternating pattern")
    void shouldHandleAlternatingPattern() {
        // Given - Alternating values
        byte[] originalData = new byte[]{1, 2, 1, 2, 1, 2};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Alternating pattern should be preserved");
        assertEquals(originalData.length * 2, compressed.length, "No compression benefit for alternating data");
    }

    @Test
    @DisplayName("should handle mixed repetitive and non-repetitive sections")
    void shouldHandleMixedData() {
        // Given
        byte[] originalData = new byte[]{1, 1, 1, 2, 3, 4, 5, 5, 5, 5};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Mixed data should be preserved");
        assertTrue(compressed.length < originalData.length * 2, "Should have some compression benefit");
    }

    @Test
    @DisplayName("should handle negative byte values correctly")
    void shouldHandleNegativeByteValues() {
        // Given - Negative bytes
        byte[] originalData = new byte[]{-1, -1, -1, -50, -50};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Negative byte values should be preserved");
    }

    @Test
    @DisplayName("should handle maximum count boundary (255)")
    void shouldHandleMaxCountBoundary() {
        // Given - Exactly 255 repetitions
        byte[] originalData = new byte[255];
        for (int i = 0; i < 255; i++) {
            originalData[i] = -128;
        }

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Exactly 255 repetitions should work correctly");
        assertEquals(2, compressed.length, "Should compress to exactly 2 bytes");
    }

    @Test
    @DisplayName("should handle large dataset with high repetition")
    void shouldHandleLargeRepetitiveDataset() {
        // Given - 10000 bytes all with the same value (100% repetition)
        byte[] largeData = new byte[10000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = 42; // All same value for maximum RLE efficiency
        }

        // When
        byte[] compressed = compressor.compress(largeData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(largeData, decompressed, "Large repetitive dataset should be preserved");
        assertTrue(compressed.length < largeData.length, "Should achieve compression on large repetitive data");
        assertEquals(80, compressed.length, "10000 repetitions should compress to 80 bytes (40 blocks of [255][value] * 2 bytes each)");
    }

    @Test
    @DisplayName("should handle multiple compression-decompression cycles")
    void shouldHandleMultipleCycles() {
        // Given
        byte[] originalData = new byte[]{5, 5, 5, 10, 10, 15};

        // When - Apply 10 cycles
        byte[] current = originalData;
        for (int i = 0; i < 10; i++) {
            current = compressor.decompress(compressor.compress(current));
        }

        // Then
        assertArrayEquals(originalData, current, "Data should remain intact after multiple cycles");
    }
}
