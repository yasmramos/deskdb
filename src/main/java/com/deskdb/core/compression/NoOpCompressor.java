package com.deskdb.core.compression;

/**
 * No-op compressor that passes data through unchanged.
 * Used when compression is disabled or for testing.
 */
public class NoOpCompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] input) {
        return input;
    }
    
    @Override
    public byte[] decompress(byte[] input) {
        return input;
    }
    
    @Override
    public String getName() {
        return "NONE";
    }
}
