package com.deskdb.core.storage.compression;

/**
 * Compresor que no aplica ninguna compresión.
 * Útil para datos que ya están comprimidos o cuando la compresión no es beneficiosa.
 */
public class NoOpCompressor implements ColumnCompressor {
    
    @Override
    public byte[] compress(byte[] data) {
        if (data == null) {
            return null;
        }
        // Retornar copia para mantener inmutabilidad
        byte[] result = new byte[data.length];
        System.arraycopy(data, 0, result, 0, data.length);
        return result;
    }
    
    @Override
    public byte[] decompress(byte[] compressedData) {
        if (compressedData == null) {
            return null;
        }
        // Retornar copia para mantener inmutabilidad
        byte[] result = new byte[compressedData.length];
        System.arraycopy(compressedData, 0, result, 0, compressedData.length);
        return result;
    }
    
    @Override
    public String getName() {
        return "NONE";
    }
}
