package com.deskdb.core.storage.compression;

/**
 * Interfaz para compresores de columnas en DeskDB.
 * Implementa algoritmos de compresión específicos para datos columnares.
 */
public interface ColumnCompressor {
    
    /**
     * Comprime un array de bytes.
     * @param data Datos originales
     * @return Datos comprimidos
     */
    byte[] compress(byte[] data);
    
    /**
     * Descomprime un array de bytes.
     * @param compressedData Datos comprimidos
     * @return Datos originales descomprimidos
     */
    byte[] decompress(byte[] compressedData);
    
    /**
     * Nombre del algoritmo de compresión.
     * @return Nombre identificador del compresor
     */
    String getName();
}
