package com.deskdb.core.storage.compression;

/**
 * Delta Encoding compressor for sequential numeric data.
 * Ideal for columns with values that increment/decrement gradually.
 * 
 * Stores the difference (delta) between consecutive values instead of full values.
 * 
 * Note: The current implementation produces output of the same size as the input
 * (one byte per delta). This means CompressionUtils.compressSmart will always
 * discard this compression since it doesn't reduce size. For this compressor to
 * be truly useful, consider packing deltas more compactly (e.g., using variable-length
 * encoding for small deltas). The current implementation maintains round-trip correctness:
 * decompress(compress(data)) == data
 */
public class DeltaCompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        
        // Write the first value complete
        output.write(data[0]);
        
        // Write deltas for the rest
        for (int i = 1; i < data.length; i++) {
            byte delta = (byte) (data[i] - data[i - 1]);
            output.write(delta);
        }
        
        return output.toByteArray();
    }
    
    @Override
    public byte[] decompress(byte[] compressedData) {
        if (compressedData == null || compressedData.length == 0) {
            return new byte[0];
        }
        
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        
        // First value is the original
        byte previous = compressedData[0];
        output.write(previous);
        
        // Reconstruct values by applying deltas
        for (int i = 1; i < compressedData.length; i++) {
            byte delta = compressedData[i];
            byte value = (byte) (previous + delta);
            output.write(value);
            previous = value;
        }
        
        return output.toByteArray();
    }
    
    @Override
    public String getName() {
        return "DELTA";
    }
}
