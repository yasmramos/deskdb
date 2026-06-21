package com.deskdb.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utilidades para cálculo de checksums y validación de integridad.
 */
public class Checksum {

    /**
     * Calcula un checksum MD5 de los datos proporcionados.
     */
    public static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 no disponible", e);
        }
    }

    /**
     * Calcula un checksum SHA-256 de los datos proporcionados.
     */
    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 no disponible", e);
        }
    }

    /**
     * Calcula un CRC32 simple de los datos proporcionados.
     */
    public static long crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Verifica si los datos coinciden con el checksum esperado.
     */
    public static boolean verify(byte[] data, String expectedChecksum, Algorithm algorithm) {
        String actualChecksum = compute(data, algorithm);
        return actualChecksum.equals(expectedChecksum);
    }

    /**
     * Calcula un checksum según el algoritmo especificado.
     */
    public static String compute(byte[] data, Algorithm algorithm) {
        switch (algorithm) {
            case MD5:
                return md5(data);
            case SHA256:
                return sha256(data);
            default:
                throw new IllegalArgumentException("Algoritmo desconocido: " + algorithm);
        }
    }

    public enum Algorithm {
        MD5, SHA256
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
