package com.deskdb.core.storage.compression;

/**
 * Compresor Delta Encoding para datos numéricos secuenciales.
 * Ideal para columnas con valores que incrementan/decrementan gradualmente.
 * 
 * Almacena la diferencia (delta) entre valores consecutivos en lugar de los valores completos.
 */
public class DeltaCompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        
        // Escribir el primer valor completo
        output.write(data[0]);
        
        // Escribir deltas para el resto
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
        
        // Primer valor es el original
        byte previous = compressedData[0];
        output.write(previous);
        
        // Reconstruir valores aplicando deltas
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
