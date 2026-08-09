package com.deskdb.core.storage.compression;

/**
 * Utilities for column compression in DeskDB.
 * Provides methods to select the best compression algorithm
 * based on data characteristics.
 */
public class CompressionUtils {
    
    private static final double RLE_THRESHOLD = 0.5; // 50% minimum repetition to use RLE
    private static final double DELTA_THRESHOLD = 0.7; // 70% sequential values to use Delta
    
    /**
     * Selects the best compressor for a dataset.
     * @param data Original data
     * @return The most suitable compressor
     */
    public static ColumnCompressor selectBestCompressor(byte[] data) {
        if (data == null || data.length < 10) {
            return new NoOpCompressor();
        }
        
        // Analyze patterns in the data
        double rleScore = calculateRLEScore(data);
        double deltaScore = calculateDeltaScore(data);
        
        if (rleScore >= RLE_THRESHOLD && rleScore >= deltaScore) {
            return new RLECompressor();
        } else if (deltaScore >= DELTA_THRESHOLD) {
            return new DeltaCompressor();
        } else {
            return new NoOpCompressor();
        }
    }
    
    /**
     * Calculates the repetition score for RLE (0-1).
     * @param data Data to analyze
     * @return Score between 0 and 1
     */
    private static double calculateRLEScore(byte[] data) {
        if (data.length < 2) {
            return 0.0;
        }
        
        int repetitions = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] == data[i - 1]) {
                repetitions++;
            }
        }
        return (double) repetitions / (data.length - 1);
    }
    
    /**
     * Calculates the sequentiality score for Delta Encoding (0-1).
     * @param data Data to analyze
     * @return Score between 0 and 1
     */
    private static double calculateDeltaScore(byte[] data) {
        if (data.length < 3) {
            return 0.0;
        }
        
        int sequential = 0;
        for (int i = 2; i < data.length; i++) {
            byte delta1 = (byte) (data[i - 1] - data[i - 2]);
            byte delta2 = (byte) (data[i] - data[i - 1]);
            
            // Consider sequential if deltas are similar
            if (Math.abs(delta1 - delta2) <= 2) {
                sequential++;
            }
        }
        return (double) sequential / (data.length - 2);
    }
    
    /**
     * Applies intelligent compression by automatically selecting the best algorithm.
     * @param data Original data
     * @return Compressed data with metadata of the compressor used
     */
    public static CompressedResult compressSmart(byte[] data) {
        if (data == null || data.length == 0) {
            return new CompressedResult(new byte[0], new NoOpCompressor());
        }
        
        ColumnCompressor compressor = selectBestCompressor(data);
        byte[] compressed = compressor.compress(data);
        
        // If compression does not reduce size, return original
        if (compressed.length >= data.length) {
            return new CompressedResult(data, new NoOpCompressor());
        }
        
        return new CompressedResult(compressed, compressor);
    }
    
    /**
     * Compression operation result.
     */
    public static class CompressedResult {
        public final byte[] data;
        public final ColumnCompressor compressor;
        
        public CompressedResult(byte[] data, ColumnCompressor compressor) {
            this.data = data;
            this.compressor = compressor;
        }
        
        /**
         * Decompresses data using the original compressor.
         * @return Decompressed data
         */
        public byte[] decompress() {
            return compressor.decompress(data);
        }
        
        /**
         * Calculates compression ratio.
         * @param originalSize Original size
         * @return Compression ratio (0-1, lower is better)
         */
        public double getCompressionRatio(int originalSize) {
            return (double) data.length / originalSize;
        }
    }
}
