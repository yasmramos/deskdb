package com.deskdb.core.compression;

/**
 * Interface for column data compression strategies.
 */
public interface ColumnCompressor {
    
    /**
     * Compresses the input data.
     * @param input The raw data to compress.
     * @return The compressed data.
     */
    byte[] compress(byte[] input);
    
    /**
     * Decompresses the input data.
     * @param input The compressed data.
     * @return The original raw data.
     */
    byte[] decompress(byte[] input);
    
    /**
     * Returns the name of the compression algorithm.
     */
    String getName();
}
