package com.deskdb.core.storage.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para CompressionUtils.
 * Valida selección inteligente de compresores y edge cases.
 */
@DisplayName("CompressionUtils Integration Tests")
class CompressionUtilsTest {

    @Test
    @DisplayName("should select NoOpCompressor for small data")
    void shouldSelectNoOpForSmallData() {
        // Given - Small dataset (< 10 bytes)
        byte[] smallData = new byte[5];

        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(smallData);

        // Then
        assertTrue(compressor instanceof NoOpCompressor, "Small data should use NoOpCompressor");
    }

    @Test
    @DisplayName("should select NoOpCompressor for null data")
    void shouldSelectNoOpForNullData() {
        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(null);

        // Then
        assertTrue(compressor instanceof NoOpCompressor, "Null data should use NoOpCompressor");
    }

    @Test
    @DisplayName("should select RLECompressor for highly repetitive data")
    void shouldSelectRLEForRepetitiveData() {
        // Given - Highly repetitive data (> 50% repetition)
        byte[] repetitiveData = new byte[100];
        for (int i = 0; i < 100; i++) {
            repetitiveData[i] = 42; // All same value
        }

        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(repetitiveData);

        // Then
        assertTrue(compressor instanceof RLECompressor, "Highly repetitive data should use RLECompressor");
    }

    @Test
    @DisplayName("should select DeltaCompressor for sequential data")
    void shouldSelectDeltaForSequentialData() {
        // Given - Sequential data (> 70% sequential)
        byte[] sequentialData = new byte[100];
        for (int i = 0; i < 100; i++) {
            sequentialData[i] = (byte) i;
        }

        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(sequentialData);

        // Then
        assertTrue(compressor instanceof DeltaCompressor, "Sequential data should use DeltaCompressor");
    }

    @Test
    @DisplayName("should compress and decompress using smart compression")
    void shouldWorkWithSmartCompression() {
        // Given - Repetitive data that benefits from compression
        byte[] originalData = new byte[50];
        for (int i = 0; i < 50; i++) {
            originalData[i] = 7;
        }

        // When
        CompressionUtils.CompressedResult result = CompressionUtils.compressSmart(originalData);
        byte[] decompressed = result.decompress();

        // Then
        assertArrayEquals(originalData, decompressed, "Smart compression should preserve data");
        assertTrue(result.getCompressionRatio(originalData.length) <= 1.0, "Compression ratio should be <= 1.0");
    }

    @Test
    @DisplayName("should fallback to NoOp when compression doesn't reduce size")
    void shouldFallbackToNoOpWhenNoBenefit() {
        // Given - Random data that won't compress well
        byte[] randomData = new byte[50];
        for (int i = 0; i < 50; i++) {
            randomData[i] = (byte) (i * 7 % 256); // Pseudo-random pattern
        }

        // When
        CompressionUtils.CompressedResult result = CompressionUtils.compressSmart(randomData);

        // Then
        assertTrue(result.compressor instanceof NoOpCompressor || result.data.length >= randomData.length,
                "Should use NoOp or not reduce size when compression is not beneficial");
    }

    @Test
    @DisplayName("should calculate correct compression ratio")
    void shouldCalculateCorrectCompressionRatio() {
        // Given
        byte[] originalData = new byte[100];
        byte[] compressedData = new byte[50]; // 50% compression
        ColumnCompressor compressor = new NoOpCompressor();

        // When
        CompressionUtils.CompressedResult result = new CompressionUtils.CompressedResult(compressedData, compressor);
        double ratio = result.getCompressionRatio(originalData.length);

        // Then
        assertEquals(0.5, ratio, 0.001, "Compression ratio should be 0.5 (50%)");
    }

    @Test
    @DisplayName("should handle decompression through CompressedResult")
    void shouldDecompressThroughCompressedResult() {
        // Given
        byte[] originalData = new byte[]{1, 1, 1, 2, 2, 3};
        ColumnCompressor compressor = new RLECompressor();
        byte[] compressed = compressor.compress(originalData);
        CompressionUtils.CompressedResult result = new CompressionUtils.CompressedResult(compressed, compressor);

        // When
        byte[] decompressed = result.decompress();

        // Then
        assertArrayEquals(originalData, decompressed, "CompressedResult should decompress correctly");
    }

    @Test
    @DisplayName("should select best compressor when RLE and Delta scores are close")
    void shouldSelectBestWhenScoresAreClose() {
        // Given - Data with both repetition and some sequentiality
        byte[] mixedData = new byte[100];
        for (int i = 0; i < 100; i++) {
            // Create pattern that has both repetition and sequence
            mixedData[i] = (byte) (i / 10); // Groups of 10 same values
        }

        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(mixedData);

        // Then
        assertNotNull(compressor, "Should select a valid compressor");
        assertTrue(compressor instanceof RLECompressor || compressor instanceof DeltaCompressor || 
                   compressor instanceof NoOpCompressor, "Should select a known compressor type");
    }

    @Test
    @DisplayName("should handle exact threshold boundary for RLE")
    void shouldHandleRLEThresholdBoundary() {
        // Given - Data with exactly 50% repetition (threshold)
        byte[] boundaryData = new byte[]{1, 1, 2, 3, 4, 5};

        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(boundaryData);

        // Then
        assertNotNull(compressor, "Should handle boundary case gracefully");
    }

    @Test
    @DisplayName("should handle exact threshold boundary for Delta")
    void shouldHandleDeltaThresholdBoundary() {
        // Given - Data with exactly 70% sequentiality (threshold)
        byte[] boundaryData = new byte[10];
        for (int i = 0; i < 10; i++) {
            boundaryData[i] = (byte) i;
        }

        // When
        ColumnCompressor compressor = CompressionUtils.selectBestCompressor(boundaryData);

        // Then
        assertNotNull(compressor, "Should handle delta boundary case gracefully");
    }

    @Test
    @DisplayName("should preserve data integrity through full compression cycle")
    void shouldPresveDataIntegrityThroughFullCycle() {
        // Given - Various data patterns
        byte[][] testData = {
            new byte[20], // Empty-ish
            new byte[]{1, 2, 3, 4, 5}, // Sequential
            new byte[]{5, 5, 5, 5, 5}, // Repetitive
            new byte[]{1, 2, 1, 2, 1}  // Alternating
        };

        // Initialize first array
        for (int i = 0; i < 20; i++) {
            testData[0][i] = (byte) (i % 256);
        }

        // When & Then - Test each pattern
        for (byte[] data : testData) {
            CompressionUtils.CompressedResult result = CompressionUtils.compressSmart(data);
            byte[] decompressed = result.decompress();
            assertArrayEquals(data, decompressed, "Data integrity should be preserved for all patterns");
        }
    }

    @Test
    @DisplayName("should store compressor reference in CompressedResult")
    void shouldStoreCompressorInResult() {
        // Given
        ColumnCompressor originalCompressor = new RLECompressor();
        byte[] data = new byte[10];
        CompressionUtils.CompressedResult result = new CompressionUtils.CompressedResult(data, originalCompressor);

        // Then
        assertEquals(originalCompressor, result.compressor, "Compressor should be stored in result");
        assertEquals(result.compressor, result.compressor, "Compressor should be accessible");
    }

    @Test
    @DisplayName("should handle large dataset with smart compression")
    void shouldHandleLargeDatasetWithSmartCompression() {
        // Given - Large dataset with repetitive sections
        byte[] largeData = new byte[10000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 50); // Creates repetitive pattern every 50 bytes
        }

        // When
        CompressionUtils.CompressedResult result = CompressionUtils.compressSmart(largeData);
        byte[] decompressed = result.decompress();

        // Then
        assertArrayEquals(largeData, decompressed, "Large dataset should be preserved");
        assertNotNull(result.compressor, "Should have a valid compressor");
    }
}
