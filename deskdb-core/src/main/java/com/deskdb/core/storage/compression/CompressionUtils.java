package com.deskdb.core.storage.compression;

/**
 * Utilidades para compresión de columnas en DeskDB.
 * Proporciona métodos para seleccionar el mejor algoritmo de compresión
 * según las características de los datos.
 */
public class CompressionUtils {
    
    private static final double RLE_THRESHOLD = 0.5; // 50% de repetición mínima para usar RLE
    private static final double DELTA_THRESHOLD = 0.7; // 70% de valores secuenciales para usar Delta
    
    /**
     * Selecciona el mejor compresor para un conjunto de datos.
     * @param data Datos originales
     * @return El compresor más adecuado
     */
    public static ColumnCompressor selectBestCompressor(byte[] data) {
        if (data == null || data.length < 10) {
            return new NoOpCompressor();
        }
        
        // Analizar patrones en los datos
        double rleScore = calculateRLEScore(data);
        double deltaScore = calculateDeltaScore(data);
        
        if (rleScore >= RLE_THRESHOLD && rleScore >= deltaScore) {
            return new RLECompressor();
        } else if (deltaScore >= DELTA_THRESHOLD) {
            return new DeltaCompressor();
        } else {
            return new NoOpCompressor();
        }
    }
    
    /**
     * Calcula el score de repetición para RLE (0-1).
     * @param data Datos a analizar
     * @return Score entre 0 y 1
     */
    private static double calculateRLEScore(byte[] data) {
        int repetitions = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] == data[i - 1]) {
                repetitions++;
            }
        }
        return (double) repetitions / (data.length - 1);
    }
    
    /**
     * Calcula el score de secuencialidad para Delta Encoding (0-1).
     * @param data Datos a analizar
     * @return Score entre 0 y 1
     */
    private static double calculateDeltaScore(byte[] data) {
        int sequential = 0;
        for (int i = 2; i < data.length; i++) {
            byte delta1 = (byte) (data[i - 1] - data[i - 2]);
            byte delta2 = (byte) (data[i] - data[i - 1]);
            
            // Considerar secuencial si los deltas son similares
            if (Math.abs(delta1 - delta2) <= 2) {
                sequential++;
            }
        }
        return (double) sequential / (data.length - 2);
    }
    
    /**
     * Aplica compresión inteligente seleccionando automáticamente el mejor algoritmo.
     * @param data Datos originales
     * @return Datos comprimidos con metadata del compresor usado
     */
    public static CompressedResult compressSmart(byte[] data) {
        ColumnCompressor compressor = selectBestCompressor(data);
        byte[] compressed = compressor.compress(data);
        
        // Si la compresión no reduce el tamaño, retornar original
        if (compressed.length >= data.length) {
            return new CompressedResult(data, new NoOpCompressor());
        }
        
        return new CompressedResult(compressed, compressor);
    }
    
    /**
     * Resultado de una operación de compresión.
     */
    public static class CompressedResult {
        public final byte[] data;
        public final ColumnCompressor compressor;
        
        public CompressedResult(byte[] data, ColumnCompressor compressor) {
            this.data = data;
            this.compressor = compressor;
        }
        
        /**
         * Descomprime los datos usando el compresor original.
         * @return Datos descomprimidos
         */
        public byte[] decompress() {
            return compressor.decompress(data);
        }
        
        /**
         * Calcula el ratio de compresión.
         * @param originalSize Tamaño original
         * @return Ratio de compresión (0-1, menor es mejor)
         */
        public double getCompressionRatio(int originalSize) {
            return (double) data.length / originalSize;
        }
    }
}
