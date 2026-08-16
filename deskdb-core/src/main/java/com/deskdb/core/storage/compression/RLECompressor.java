package com.deskdb.core.storage.compression;

import java.util.Arrays;

/**
 * Run-Length Encoding (RLE) compressor for columnar data.
 * Ideal for columns with many consecutive repeated values.
 * 
 * Format: [count][value] repeated
 * Where count is a byte (1-255) and value are the bytes of the original value.
 */
public class RLECompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        
        // Initialize with estimated capacity to reduce reallocations
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(data.length);
        
        int i = 0;
        while (i < data.length) {
            byte current = data[i];
            int count = 1;
            
            // Count consecutive repetitions (max 255)
            while (i + count < data.length && data[i + count] == current && count < 255) {
                count++;
            }
            
            // Write count and value
            output.write(count);
            output.write(current);
            
            i += count;
        }
        
        return output.toByteArray();
    }
    
    @Override
    public byte[] decompress(byte[] compressedData) {
        if (compressedData == null || compressedData.length == 0) {
            return new byte[0];
        }
        
        // First pass: calculate total decompressed size
        int totalSize = 0;
        int i = 0;
        while (i < compressedData.length) {
            // Validate that we have at least 2 bytes (count + value)
            if (i + 1 >= compressedData.length) {
                // Corrupt or odd-length data, stop decompression
                break;
            }
            
            int count = compressedData[i] & 0xFF; // Convert to unsigned
            totalSize += count;
            i += 2;
        }
        
        // Allocate exact size buffer
        byte[] output = new byte[totalSize];
        int pos = 0;
        
        // Second pass: fill the output buffer efficiently
        i = 0;
        while (i < compressedData.length) {
            if (i + 1 >= compressedData.length) {
                break;
            }
            
            int count = compressedData[i] & 0xFF;
            byte value = compressedData[i + 1];
            
            // Fill the output buffer with the value repeated 'count' times
            Arrays.fill(output, pos, pos + count, value);
            pos += count;
            
            i += 2;
        }
        
        return output;
    }
    
    @Override
    public String getName() {
        return "RLE";
    }
}
