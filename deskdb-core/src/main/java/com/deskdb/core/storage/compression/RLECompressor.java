package com.deskdb.core.storage.compression;

/**
 * Compresor Run-Length Encoding (RLE) para datos columnares.
 * Ideal para columnas con muchos valores repetidos consecutivos.
 * 
 * Formato: [count][value] repeated
 * Donde count es un byte (1-255) y value son los bytes del valor original.
 */
public class RLECompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        
        // Buffer temporal (puede crecer, se ajusta al final)
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        
        int i = 0;
        while (i < data.length) {
            byte current = data[i];
            int count = 1;
            
            // Contar repeticiones consecutivas (máximo 255)
            while (i + count < data.length && data[i + count] == current && count < 255) {
                count++;
            }
            
            // Escribir count y valor
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
        
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        
        int i = 0;
        while (i < compressedData.length) {
            int count = compressedData[i] & 0xFF; // Convertir a unsigned
            byte value = compressedData[i + 1];
            
            // Repetir el valor 'count' veces
            for (int j = 0; j < count; j++) {
                output.write(value);
            }
            
            i += 2;
        }
        
        return output.toByteArray();
    }
    
    @Override
    public String getName() {
        return "RLE";
    }
}
