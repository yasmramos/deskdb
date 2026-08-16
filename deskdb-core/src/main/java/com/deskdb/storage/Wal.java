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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log (WAL) optimized for high performance.
 * 
 * Improvements implemented:
 * 1. Write buffering to batch operations
 * 2. Async flush in background thread using ScheduledExecutorService for deterministic scheduling
 * 3. Commit batching to reduce fsync calls
 * 4. Elimination of excessive synchronization
 * 5. Complete buffer drainage per flush cycle to reduce variance
 */
public class Wal implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Wal.class);
    
    // Magic bytes para identificar el archivo WAL
    private static final byte[] MAGIC_BYTES = "DESKDB_WAL".getBytes();
    private static final int HEADER_SIZE = 20; // 10 magic + 4 version + 4 checksum + 2 length
    
    // Configuración de buffering optimizada para alto rendimiento
    private static final int DEFAULT_BUFFER_SIZE = 4096; // Número máximo de entradas en buffer
    private static final long FLUSH_INTERVAL_MS = 5; // Intervalo de flush en ms (reducido para baja latencia)
    private static final int BATCH_COMMIT_THRESHOLD = 100; // Commit después de N operaciones
    private static final int MIN_BATCH_SIZE = 8; // Mínimo de entradas para hacer flush eficiente
    
    private final Path walPath;
    private final FileChannel channel;
    private long position = 0;
    private boolean closed = false;
    
    // Getter for walPath
    public Path getWalPath() {
        return walPath;
    }
    
    // Buffer de escrituras pendientes
    private final BlockingQueue<WalEntry> writeBuffer;
    private final ScheduledExecutorService flushExecutor;
    private final AtomicBoolean flushScheduled;
    private volatile boolean needsForce = false;
    
    // Contador atómico de operaciones pendientes de commit
    private final AtomicInteger pendingOperations = new AtomicInteger(0);
    private final Object commitLock = new Object();
    
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
        
        // Initialize buffer and executor for async flush using ScheduledExecutorService for deterministic scheduling
        this.writeBuffer = new ArrayBlockingQueue<>(DEFAULT_BUFFER_SIZE);
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WAL-Flush-Thread");
            t.setDaemon(true);
            return t;
        });
        this.flushScheduled = new AtomicBoolean(false);
        
        // Do not start periodic flush thread to avoid issues in tests
        // Flush will be performed synchronously when needed
        
        logger.info("WAL initialized at {} (sync mode)", walPath.toAbsolutePath());
    }
    
    /**
     * Inicia el thread de flush periódico en background
     */
    private void startPeriodicFlush() {
        flushExecutor.submit(() -> {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                try {
                    // Esperar un breve intervalo antes de verificar si hay datos
                    Thread.sleep(FLUSH_INTERVAL_MS);
                    
                    // Si hay operaciones pendientes, hacer flush
                    if (pendingOperations.get() > 0 || !writeBuffer.isEmpty()) {
                        doFlush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in periodic flush: {}", e.getMessage());
                }
            }
            
            // Flush final antes de terminar
            try {
                doFlush();
            } catch (IOException e) {
                logger.error("Error in final flush: {}", e.getMessage());
            }
        });
    }
    
    /**
     * Crea o abre un WAL en la ruta especificada
     */
    public static Wal open(Path walPath) throws IOException {
        return new Wal(walPath);
    }
    
    /**
     * Escribe una entrada en el WAL de forma asíncrona y bufferizada
     */
    public void write(long transactionId, OperationType operation, 
                                   String tableName, String key, byte[] data) throws IOException {
        checkClosed();
        
        long timestamp = System.currentTimeMillis();
        WalEntry entry = new WalEntry(timestamp, transactionId, operation, tableName, key, data);
        
        // Añadir al buffer y hacer flush asíncrono para alto rendimiento
        if (!writeBuffer.offer(entry)) {
            // Buffer lleno, hacer flush síncrono
            synchronized (this) {
                doFlush();
                writeBuffer.offer(entry);
                pendingOperations.incrementAndGet();
            }
        } else {
            pendingOperations.incrementAndGet();
            scheduleFlush();
        }
        
        logger.trace("WAL entry written: tx={}, op={}, table={}, key={}", 
                    transactionId, operation, tableName, key);
    }
    
    /**
     * Schedules an async flush if none is pending.
     * Uses ScheduledExecutorService.schedule() with FLUSH_INTERVAL_MS delay for deterministic group commit batching.
     * This eliminates the non-deterministic Thread.sleep(1) jitter that caused benchmark variance.
     */
    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            flushExecutor.schedule(() -> {
                try {
                    doFlush();
                } catch (IOException e) {
                    logger.error("Error in scheduled flush: {}", e.getMessage());
                } finally {
                    flushScheduled.set(false);
                }
            }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * Performs flush of all buffered entries to disk with optimized batching.
     * Drains the buffer completely in a loop to ensure all pending entries are processed in one flush cycle,
     * reducing variance by avoiding partial flushes that leave entries waiting for the next cycle.
     */
    private synchronized void doFlush() throws IOException {
        if (writeBuffer.isEmpty() && pendingOperations.get() == 0) {
            return;
        }
        
        // Drain and process all entries in the buffer in a loop to ensure complete drainage per flush cycle
        int totalFlushed = 0;
        List<WalEntry> batch = new ArrayList<>();
        
        while (!writeBuffer.isEmpty()) {
            batch.clear();
            writeBuffer.drainTo(batch, BATCH_COMMIT_THRESHOLD);
            
            if (batch.isEmpty()) {
                break;
            }
            
            // Write all entries in the batch
            for (WalEntry entry : batch) {
                ByteBuffer buffer = serializeEntry(entry);
                channel.position(position);
                channel.write(buffer);
                position += buffer.limit();
            }
            
            totalFlushed += batch.size();
        }
        
        // Force write to disk only once at the end of the cycle to avoid multiple fsync calls
        if (totalFlushed > 0) {
            channel.force(false);
            pendingOperations.set(Math.max(0, pendingOperations.get() - totalFlushed));
            logger.debug("Flushed {} entries to WAL (total pending: {})", totalFlushed, pendingOperations.get());
        } else if (pendingOperations.get() > 0) {
            // No new entries but there are pending operations
            // This can happen if entries were already written but not flushed
            channel.force(false);
            pendingOperations.set(0);
        }
    }
    
    /**
     * Escribe una operación de commit con opción de flush inmediato según nivel de durabilidad.
     * @param transactionId el ID de la transacción
     * @param forceSync si true, fuerza fsync inmediato (modo SAFE); si false, bufferiza para group commit (modo NORMAL/ASYNC)
     * @throws IOException si ocurre un error de E/S
     */
    public synchronized void writeCommit(long transactionId, boolean forceSync) throws IOException {
        // Escribir COMMIT como entrada normal (se bufferiza)
        write(transactionId, OperationType.COMMIT, "", "", new byte[0]);
        
        // Forzar flush inmediato solo si se requiere durabilidad estricta (SAFE mode)
        if (forceSync) {
            doFlush();
            logger.info("Transaction {} committed to WAL (fsync immediate)", transactionId);
        } else {
            logger.debug("Transaction {} committed to WAL (buffered for group commit)", transactionId);
        }
    }
    
    /**
     * Escribe una operación de commit con flush inmediato para garantizar durabilidad (compatibilidad).
     * Delega en writeCommit(transactionId, true) para preservar comportamiento anterior.
     * @param transactionId el ID de la transacción
     * @throws IOException si ocurre un error de E/S
     */
    public synchronized void writeCommit(long transactionId) throws IOException {
        writeCommit(transactionId, true);
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
                int bytesRead = channel.read(buffer);
                
                // Check if we read less than 4 bytes (EOF or corruption)
                if (bytesRead < 4) {
                    logger.warn("WAL read: incomplete length header ({} bytes), possible corruption or EOF", bytesRead);
                    break;
                }
                
                buffer.flip();
                int entryLength = buffer.getInt();
                
                if (entryLength <= 0 || entryLength > 10 * 1024 * 1024) { // Máximo 10MB por entrada
                    logger.warn("Entrada WAL con longitud inválida: {}", entryLength);
                    break;
                }
                
                ByteBuffer entryBuffer = ByteBuffer.allocate(entryLength);
                int entryBytesRead = channel.read(entryBuffer);
                
                // Verify we read the complete entry
                if (entryBytesRead != entryLength) {
                    logger.warn("WAL read: incomplete entry (expected {} bytes, got {}), possible corruption", 
                               entryLength, entryBytesRead);
                    break;
                }
                
                entryBuffer.flip();
                
                WalEntry entry = deserializeEntry(entryBuffer);
                if (entry != null) {
                    entries.add(entry);
                } else {
                    logger.warn("Skipping corrupted WAL entry at position {}", channel.position());
                    break; // Detener reproducción ante corrupción
                }
                
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
        // Esperar a que el buffer esté vacío antes de truncar
        doFlush();
        channel.truncate(0);
        position = 0;
        writeHeader();
        logger.info("WAL truncated");
    }
    
    /**
     * Closes the WAL, performing a final flush of all pending operations.
     * Cancels any scheduled tasks and ensures durability with a final fsync before closing the channel.
     */
    public synchronized void close() throws IOException {
        if (!closed) {
            // Final flush of all pending operations
            doFlush();
            
            // Shutdown the executor, canceling any pending scheduled tasks
            if (flushExecutor != null && !flushExecutor.isShutdown()) {
                flushExecutor.shutdown();
                try {
                    if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        flushExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    flushExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            channel.force(true); // Ensure all data is on disk
            channel.close();
            closed = true;
            logger.info("WAL closed after flushing {} pending operations", pendingOperations.get());
        }
    }
    
    public synchronized void flush() throws IOException {
        doFlush();
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
        buffer.putInt(1); // Version
        buffer.putInt(0); // Checksum placeholder
        buffer.putShort((short) 0); // Reserved
        
        // Calculate checksum over header (excluding checksum field itself)
        buffer.flip();
        int checksum = calculateChecksum(buffer);
        
        // Rewrite with correct checksum
        buffer.clear();
        buffer.put(MAGIC_BYTES);
        buffer.putInt(1); // Version
        buffer.putInt(checksum); // Actual checksum
        buffer.putShort((short) 0); // Reserved
        buffer.flip();
        
        channel.position(0);
        channel.write(buffer);
        channel.force(false);
    }
    
    private void validateHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        channel.position(0);
        if (channel.read(buffer) < HEADER_SIZE) {
            throw new IOException("WAL file too small: corrupted header");
        }
        buffer.flip();
        
        byte[] magic = new byte[MAGIC_BYTES.length];
        buffer.get(magic);
        
        if (!java.util.Arrays.equals(MAGIC_BYTES, magic)) {
            throw new IOException("Invalid WAL file: incorrect magic bytes");
        }
        
        int version = buffer.getInt();
        if (version != 1) {
            throw new IOException("Unsupported WAL version: " + version);
        }
        
        // Verify checksum
        int storedChecksum = buffer.getInt();
        buffer.flip();
        int calculatedChecksum = calculateChecksum(buffer);
        
        if (storedChecksum != calculatedChecksum) {
            throw new IOException("Invalid WAL file: checksum mismatch");
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
            logger.warn("Checksum mismatch en entrada WAL: stored=0x{:08X}, calculated=0x{:08X}", 
                       Integer.toHexString(storedChecksum), Integer.toHexString(calculatedChecksum));
            return null; // Señalar corrupción para que el llamador descarte esta entrada
        }
        
        return new WalEntry(timestamp, transactionId, operation, tableName, key, data);
    }
    
    private int calculateChecksum(ByteBuffer buffer) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        int position = buffer.position();
        int limit = buffer.limit() - 4; // Excluir checksum almacenado
        
        // Actualizar CRC con los bytes desde position hasta limit
        for (int i = position; i < limit; i++) {
            crc.update(buffer.get(i) & 0xFF);
        }
        
        return (int) crc.getValue();
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
