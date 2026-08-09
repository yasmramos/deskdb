package com.deskdb.core.storage.compression;

/**
 * Compressor that applies no compression.
 * Useful for data that is already compressed or when compression is not beneficial.
 */
public class NoOpCompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] data) {
        if (data == null) {
            return new byte[0];
        }
        // Return copy to maintain immutability
        byte[] result = new byte[data.length];
        System.arraycopy(data, 0, result, 0, data.length);
        return result;
    }
    
    @Override
    public byte[] decompress(byte[] compressedData) {
        if (compressedData == null) {
            return new byte[0];
        }
        // Return copy to maintain immutability
        byte[] result = new byte[compressedData.length];
        System.arraycopy(compressedData, 0, result, 0, compressedData.length);
        return result;
    }
    
    @Override
    public String getName() {
        return "NONE";
    }
}
