package com.deskdb.storage;

import com.deskdb.core.compression.ColumnCompressor;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Utility class for compressing and decompressing column data blocks.
 */
public class CompressionUtils {
    
    private CompressionUtils() {
        // Utility class
    }
    
    /**
     * Compresses the data in a ByteBuffer using the provided compressor.
     * The compressed data is written to a new buffer.
     * 
     * @param source The source buffer containing uncompressed data
     * @param compressor The compression algorithm to use
     * @return A new buffer containing the compressed data
     */
    public static ByteBuffer compress(ByteBuffer source, ColumnCompressor compressor) {
        if (source == null || compressor == null) {
            return source;
        }
        
        // Save current position
        int originalPosition = source.position();
        
        // Read all data from source
        byte[] data = new byte[source.remaining()];
        source.get(data);
        
        // Compress the data
        byte[] compressedData = compressor.compress(data);
        
        // Create new buffer with compressed data
        ByteBuffer result = ByteBuffer.allocate(compressedData.length + 8);
        result.putInt(data.length); // Store original size
        result.putInt(compressedData.length); // Store compressed size
        result.put(compressedData);
        result.flip();
        
        // Restore source position
        source.position(originalPosition);
        
        return result;
    }
    
    /**
     * Decompresses the data in a ByteBuffer.
     * 
     * @param source The source buffer containing compressed data
     * @param compressor The compression algorithm used
     * @return A new buffer containing the decompressed data
     */
    public static ByteBuffer decompress(ByteBuffer source, ColumnCompressor compressor) {
        if (source == null || compressor == null) {
            return source;
        }
        
        // Read metadata
        int originalSize = source.getInt();
        int compressedSize = source.getInt();
        
        // Read compressed data
        byte[] compressedData = new byte[compressedSize];
        source.get(compressedData);
        
        // Decompress
        byte[] decompressedData = compressor.decompress(compressedData);
        
        // Verify size
        if (decompressedData.length != originalSize) {
            throw new RuntimeException("Decompression size mismatch: expected " + 
                originalSize + ", got " + decompressedData.length);
        }
        
        return ByteBuffer.wrap(decompressedData);
    }
    
    /**
     * Calculates the compression ratio achieved.
     * 
     * @param originalSize The original uncompressed size
     * @param compressedSize The compressed size
     * @return The compression ratio (0-1, where lower is better)
     */
    public static double getCompressionRatio(int originalSize, int compressedSize) {
        if (originalSize <= 0) {
            return 1.0;
        }
        return (double) compressedSize / originalSize;
    }
    
    /**
     * Estimates whether compression is beneficial for the given data.
     * Uses simple heuristics based on data patterns.
     * 
     * @param data The data to analyze
     * @return true if compression is likely to be beneficial
     */
    public static boolean shouldCompress(byte[] data) {
        if (data == null || data.length < 64) {
            return false; // Don't compress very small data
        }
        
        // Check for repeated patterns (good for RLE)
        int repeats = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] == data[i - 1]) {
                repeats++;
            }
        }
        
        // If more than 30% of bytes are repeats, compression is likely beneficial
        return (double) repeats / data.length > 0.3;
    }
}
