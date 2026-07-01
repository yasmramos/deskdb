package com.deskdb.core.compression;

/**
 * Run-Length Encoding (RLE) compressor for columnar data.
 * Effective for columns with many consecutive repeated values.
 */
public class RLECompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        
        // Use a simple approach: [count][value] pairs
        // For better performance, use ByteArrayOutputStream in production
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        
        int i = 0;
        while (i < input.length) {
            byte current = input[i];
            int count = 1;
            
            // Count consecutive identical bytes
            while (i + count < input.length && input[i + count] == current && count < 255) {
                count++;
            }
            
            // Write count and value
            baos.write(count);
            baos.write(current & 0xFF);
            
            i += count;
        }
        
        return baos.toByteArray();
    }
    
    @Override
    public byte[] decompress(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        
        int i = 0;
        while (i < input.length - 1) {
            int count = input[i] & 0xFF;
            byte value = input[i + 1];
            
            for (int j = 0; j < count; j++) {
                baos.write(value);
            }
            
            i += 2;
        }
        
        return baos.toByteArray();
    }
    
    @Override
    public String getName() {
        return "RLE";
    }
}
