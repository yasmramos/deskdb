package com.deskdb.core.compression;

/**
 * Delta Encoding compressor for numeric columnar data.
 * Stores differences between consecutive values instead of absolute values.
 * Effective for monotonically increasing/decreasing sequences.
 */
public class DeltaCompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        
        // Write the first value as-is
        baos.write(input[0]);
        
        // Write deltas for subsequent values
        for (int i = 1; i < input.length; i++) {
            byte delta = (byte)(input[i] - input[i - 1]);
            baos.write(delta);
        }
        
        return baos.toByteArray();
    }
    
    @Override
    public byte[] decompress(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        
        // First value is stored as-is
        byte previous = input[0];
        baos.write(previous);
        
        // Reconstruct values from deltas
        for (int i = 1; i < input.length; i++) {
            byte delta = input[i];
            byte value = (byte)(previous + delta);
            baos.write(value);
            previous = value;
        }
        
        return baos.toByteArray();
    }
    
    @Override
    public String getName() {
        return "DELTA";
    }
}
