package com.deskdb.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-Ahead Log (WAL) para garantizar durabilidad y recuperación ante fallos.
 * 
 * El WAL registra todas las operaciones antes de aplicarlas al archivo principal,
 * permitiendo recuperar el estado en caso de fallo.
 */
public class Wal implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Wal.class);
    
    // Magic bytes para identificar el archivo WAL
    private static final byte[] MAGIC_BYTES = "DESKDB_WAL".getBytes();
    private static final int HEADER_SIZE = 20; // 10 magic + 4 version + 4 checksum + 2 length
    
    private final Path walPath;
    private final FileChannel channel;
    private long position = 0;
    private boolean closed = false;
    
    /**
     * Tipos de operaciones registradas en el WAL
     */
    public enum OperationType {
        INSERT((byte) 0x01),
        UPDATE((byte) 0x02),
        DELETE((byte) 0x03),
        COMMIT((byte) 0x10),
        ROLLBACK((byte) 0x11),
        CHECKPOINT((byte) 0x20);
        
        private final byte code;
        
        OperationType(byte code) {
            this.code = code;
        }
        
        public byte getCode() {
            return code;
        }
        
        public static OperationType fromCode(byte code) {
            for (OperationType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Código de operación desconocido: " + code);
        }
    }
    
    /**
     * Entrada del WAL que representa una operación
     */
    public static class WalEntry {
        public final long timestamp;
        public final long transactionId;
        public final OperationType operation;
        public final String tableName;
        public final String key;
        public final byte[] data;
        
        public WalEntry(long timestamp, long transactionId, OperationType operation,
                       String tableName, String key, byte[] data) {
            this.timestamp = timestamp;
            this.transactionId = transactionId;
            this.operation = operation;
            this.tableName = tableName;
            this.key = key;
            this.data = data;
        }
    }
    
    private Wal(Path walPath) throws IOException {
        this.walPath = walPath;
        
        // Asegurar que el directorio padre existe
        if (walPath.getParent() != null && !Files.exists(walPath.getParent())) {
            Files.createDirectories(walPath.getParent());
        }
        
        File walFile = walPath.toFile();
        boolean isNew = !walFile.exists();
        
        this.channel = new RandomAccessFile(walFile, "rw").getChannel();
        
        if (isNew) {
            writeHeader();
        } else {
            validateHeader();
            position = channel.size();
        }
        
        logger.info("WAL initialized at {}", walPath.toAbsolutePath());
    }
    
    /**
     * Crea o abre un WAL en la ruta especificada
     */
    public static Wal open(Path walPath) throws IOException {
        return new Wal(walPath);
    }
    
    /**
     * Escribe una entrada en el WAL
     */
    public synchronized void write(long transactionId, OperationType operation, 
                                   String tableName, String key, byte[] data) throws IOException {
        checkClosed();
        
        long timestamp = System.currentTimeMillis();
        WalEntry entry = new WalEntry(timestamp, transactionId, operation, tableName, key, data);
        
        ByteBuffer buffer = serializeEntry(entry);
        
        // Escribir en el WAL
        channel.position(position);
        channel.write(buffer);
        channel.force(false); // Forzar escritura al disco
        
        position += buffer.limit();
        
        logger.trace("WAL entry written: tx={}, op={}, table={}, key={}", 
                    transactionId, operation, tableName, key);
    }
    
    /**
     * Escribe una operación de commit
     */
    public synchronized void writeCommit(long transactionId) throws IOException {
        write(transactionId, OperationType.COMMIT, "", "", new byte[0]);
    }
    
    /**
     * Escribe una operación de rollback
     */
    public synchronized void writeRollback(long transactionId) throws IOException {
        write(transactionId, OperationType.ROLLBACK, "", "", new byte[0]);
    }
    
    /**
     * Escribe un checkpoint (punto de recuperación)
     */
    public synchronized void writeCheckpoint(long transactionId) throws IOException {
        write(transactionId, OperationType.CHECKPOINT, "", "", new byte[0]);
    }
    
    /**
     * Lee todas las entradas del WAL desde el principio
     */
    public synchronized List<WalEntry> readAll() throws IOException {
        checkClosed();
        
        List<WalEntry> entries = new ArrayList<>();
        channel.position(HEADER_SIZE); // Saltar header
        
        while (channel.position() < channel.size()) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(4);
                channel.read(buffer);
                
                if (buffer.limit() < 4) {
                    break; // Fin del archivo o entrada corrupta
                }
                
                buffer.flip();
                int entryLength = buffer.getInt();
                
                if (entryLength <= 0 || entryLength > 10 * 1024 * 1024) { // Máximo 10MB por entrada
                    logger.warn("Entrada WAL con longitud inválida: {}", entryLength);
                    break;
                }
                
                ByteBuffer entryBuffer = ByteBuffer.allocate(entryLength);
                channel.read(entryBuffer);
                entryBuffer.flip();
                
                WalEntry entry = deserializeEntry(entryBuffer);
                entries.add(entry);
                
            } catch (Exception e) {
                logger.warn("Error al leer entrada WAL, posible corrupción: {}", e.getMessage());
                break;
            }
        }
        
        logger.debug("Read {} entries from WAL", entries.size());
        return entries;
    }
    
    /**
     * Trunca el WAL (después de un checkpoint exitoso)
     */
    public synchronized void truncate() throws IOException {
        checkClosed();
        channel.truncate(0);
        position = 0;
        writeHeader();
        logger.info("WAL truncated");
    }
    
    /**
     * Cierra el WAL
     */
    public synchronized void close() throws IOException {
        if (!closed) {
            channel.close();
            closed = true;
            logger.info("WAL closed");
        }
    }
    
    /**
     * Verifica si el WAL está cerrado
     */
    public boolean isClosed() {
        return closed;
    }
    
    /**
     * Obtiene el número de entradas en el WAL
     */
    public synchronized int size() throws IOException {
        return readAll().size();
    }
    
    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("WAL está cerrado");
        }
    }
    
    private void writeHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.put(MAGIC_BYTES);
        buffer.putInt(1); // Versión
        buffer.putInt(0); // Checksum (placeholder)
        buffer.putShort((short) 0); // Reserved
        buffer.flip();
        
        channel.position(0);
        channel.write(buffer);
        channel.force(false);
    }
    
    private void validateHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        channel.position(0);
        channel.read(buffer);
        buffer.flip();
        
        byte[] magic = new byte[10];
        buffer.get(magic);
        
        if (!java.util.Arrays.equals(MAGIC_BYTES, magic)) {
            throw new IOException("Archivo WAL inválido: magic bytes incorrectos");
        }
        
        int version = buffer.getInt();
        if (version != 1) {
            throw new IOException("Versión de WAL no soportada: " + version);
        }
    }
    
    private ByteBuffer serializeEntry(WalEntry entry) {
        byte[] tableNameBytes = entry.tableName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBytes = entry.key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        int totalSize = 4 + // Longitud total
                       8 + // Timestamp
                       8 + // Transaction ID
                       1 + // Operation type
                       2 + // Longitud nombre tabla
                       tableNameBytes.length +
                       2 + // Longitud clave
                       keyBytes.length +
                       4 + // Longitud datos
                       entry.data.length +
                       4;  // Checksum
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        // Reservar espacio para longitud total
        int startPosition = buffer.position();
        buffer.putInt(0); // Placeholder
        
        buffer.putLong(entry.timestamp);
        buffer.putLong(entry.transactionId);
        buffer.put(entry.operation.getCode());
        
        buffer.putShort((short) tableNameBytes.length);
        buffer.put(tableNameBytes);
        
        buffer.putShort((short) keyBytes.length);
        buffer.put(keyBytes);
        
        buffer.putInt(entry.data.length);
        if (entry.data.length > 0) {
            buffer.put(entry.data);
        }
        
        // Calcular checksum
        buffer.position(startPosition + 4); // Saltar longitud
        int checksum = calculateChecksum(buffer);
        
        // Volver al inicio y escribir longitud total y checksum
        buffer.position(startPosition);
        buffer.putInt(totalSize - 4); // Excluir el campo de longitud
        buffer.position(totalSize - 4); // Ir al checksum
        buffer.putInt(checksum);
        
        buffer.flip();
        return buffer;
    }
    
    private WalEntry deserializeEntry(ByteBuffer buffer) {
        long timestamp = buffer.getLong();
        long transactionId = buffer.getLong();
        byte opCode = buffer.get();
        OperationType operation = OperationType.fromCode(opCode);
        
        short tableNameLen = buffer.getShort();
        byte[] tableNameBytes = new byte[tableNameLen];
        buffer.get(tableNameBytes);
        String tableName = new String(tableNameBytes, java.nio.charset.StandardCharsets.UTF_8);
        
        short keyLen = buffer.getShort();
        byte[] keyBytes = new byte[keyLen];
        buffer.get(keyBytes);
        String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
        
        int dataLen = buffer.getInt();
        byte[] data = new byte[dataLen];
        if (dataLen > 0) {
            buffer.get(data);
        }
        
        // Verificar checksum
        int storedChecksum = buffer.getInt();
        buffer.rewind();
        int calculatedChecksum = calculateChecksum(buffer);
        
        if (storedChecksum != calculatedChecksum) {
            logger.warn("Checksum mismatch en entrada WAL");
        }
        
        return new WalEntry(timestamp, transactionId, operation, tableName, key, data);
    }
    
    private int calculateChecksum(ByteBuffer buffer) {
        int checksum = 0;
        int position = buffer.position();
        int limit = buffer.limit() - 4; // Excluir checksum almacenado
        
        buffer.position(position);
        for (int i = 0; i < limit - position; i++) {
            checksum ^= (buffer.get() & 0xFF) << ((i % 4) * 8);
        }
        
        buffer.position(position);
        return checksum;
    }
    
    /**
     * Recupera el estado de la base de datos aplicando el WAL
     * Devuelve las entradas que deben ser aplicadas
     */
    public static List<WalEntry> recover(Path walPath) throws IOException {
        if (!Files.exists(walPath)) {
            return new ArrayList<>();
        }
        
        try (Wal wal = Wal.open(walPath)) {
            List<WalEntry> allEntries = wal.readAll();
            List<WalEntry> pendingEntries = new ArrayList<>();
            
            // Encontrar transacciones no comprometidas
            java.util.Set<Long> committedTransactions = new java.util.HashSet<>();
            java.util.Set<Long> rolledbackTransactions = new java.util.HashSet<>();
            
            for (WalEntry entry : allEntries) {
                if (entry.operation == OperationType.COMMIT) {
                    committedTransactions.add(entry.transactionId);
                } else if (entry.operation == OperationType.ROLLBACK) {
                    rolledbackTransactions.add(entry.transactionId);
                }
            }
            
            // Solo mantener entradas de transacciones comprometidas
            for (WalEntry entry : allEntries) {
                if (entry.operation == OperationType.COMMIT || 
                    entry.operation == OperationType.ROLLBACK ||
                    entry.operation == OperationType.CHECKPOINT) {
                    continue;
                }
                
                if (committedTransactions.contains(entry.transactionId)) {
                    pendingEntries.add(entry);
                }
            }
            
            logger.info("Recovery: {} entries total, {} pending application", 
                       allEntries.size(), pendingEntries.size());
            
            return pendingEntries;
        }
    }
}
