package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para DeltaCompressor.
 * Valida compresión Delta Encoding, edge cases y rendimiento.
 */
@DisplayName("DeltaCompressor Integration Tests")
class DeltaCompressorTest {

    private final DeltaCompressor compressor = new DeltaCompressor();

    @Test
    @DisplayName("should compress and decompress preserving original data")
    void shouldPreserveDataAfterCompressionAndDecompression() {
        // Given - Sequential data (ideal for Delta)
        byte[] originalData = new byte[]{10, 11, 12, 13, 14, 15};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Decompressed data should match original");
        assertEquals(originalData.length, compressed.length, "Delta encoding maintains same size for sequential data");
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
    @DisplayName("should handle single element array")
    void shouldHandleSingleElement() {
        // Given
        byte[] originalData = new byte[]{42};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Single element should be preserved");
        assertEquals(1, compressed.length, "Single element should remain 1 byte");
    }

    @Test
    @DisplayName("should handle constant values efficiently")
    void shouldHandleConstantValues() {
        // Given - All same values (delta = 0)
        byte[] originalData = new byte[10];
        for (int i = 0; i < 10; i++) {
            originalData[i] = 5;
        }

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Constant values should be preserved");
        assertEquals(originalData.length, compressed.length, "Delta encoding stores first value + zeros");
    }

    @Test
    @DisplayName("should handle decreasing sequence")
    void shouldHandleDecreasingSequence() {
        // Given
        byte[] originalData = new byte[]{100, 90, 80, 70, 60};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Decreasing sequence should be preserved");
    }

    @Test
    @DisplayName("should handle negative deltas correctly")
    void shouldHandleNegativeDeltas() {
        // Given - Large decrease
        byte[] originalData = new byte[]{100, 50, 10, 5, 1};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Large negative deltas should be handled correctly");
    }

    @Test
    @DisplayName("should handle overflow/underflow in byte arithmetic")
    void shouldHandleByteOverflow() {
        // Given - Values that cause overflow when calculating delta
        byte[] originalData = new byte[]{127, -128, 126, -127};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Byte overflow should be handled correctly via wraparound");
    }

    @Test
    @DisplayName("should return correct name identifier")
    void shouldReturnCorrectName() {
        // When
        String name = compressor.getName();

        // Then
        assertEquals("DELTA", name, "Name should be 'DELTA'");
    }

    @Test
    @DisplayName("should handle random pattern data")
    void shouldHandleRandomPattern() {
        // Given - Random-looking data (values must be in byte range -128 to 127)
        byte[] originalData = new byte[]{5, 92, 17, -53, 88, 41, -100, 29};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Random pattern should be preserved");
    }

    @Test
    @DisplayName("should handle large sequential dataset")
    void shouldHandleLargeSequentialDataset() {
        // Given - 10000 sequential bytes
        byte[] largeData = new byte[10000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        // When
        byte[] compressed = compressor.compress(largeData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(largeData, decompressed, "Large sequential dataset should be preserved");
    }

    @Test
    @DisplayName("should handle zigzag pattern")
    void shouldHandleZigZagPattern() {
        // Given - Up and down pattern
        byte[] originalData = new byte[]{10, 20, 15, 25, 20, 30, 25};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Zigzag pattern should be preserved");
    }

    @Test
    @DisplayName("should handle multiple compression-decompression cycles")
    void shouldHandleMultipleCycles() {
        // Given
        byte[] originalData = new byte[]{1, 3, 5, 7, 9};

        // When - Apply 10 cycles
        byte[] current = originalData;
        for (int i = 0; i < 10; i++) {
            current = compressor.decompress(compressor.compress(current));
        }

        // Then
        assertArrayEquals(originalData, current, "Data should remain intact after multiple cycles");
    }

    @Test
    @DisplayName("should handle boundary values (min and max byte)")
    void shouldHandleBoundaryValues() {
        // Given - Min and max byte values
        byte[] originalData = new byte[]{Byte.MIN_VALUE, Byte.MAX_VALUE, 0, -1};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Boundary values should be preserved");
    }

    @Test
    @DisplayName("should handle two-element array")
    void shouldHandleTwoElements() {
        // Given
        byte[] originalData = new byte[]{50, 100};

        // When
        byte[] compressed = compressor.compress(originalData);
        byte[] decompressed = compressor.decompress(compressed);

        // Then
        assertArrayEquals(originalData, decompressed, "Two-element array should be preserved");
        assertEquals(2, compressed.length, "Two elements should remain 2 bytes");
    }
}
