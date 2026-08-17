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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
    
    // Buffer de escrituras pendientes - ConcurrentLinkedQueue para writers no bloqueantes (MPSC lock-free)
    private final ConcurrentLinkedQueue<WalEntry> writeBuffer;
    private final ScheduledExecutorService flushExecutor;
    private final AtomicBoolean flushScheduled;
    private volatile boolean needsForce = false;
    
    // Contador atómico de operaciones pendientes de commit
    private final AtomicInteger pendingOperations = new AtomicInteger(0);
    
    // Lock privado del flusher para serializar ciclos periódicos vs flushes forzados SAFE
    // Este lock NUNCA es retenido por los writers, solo por el flusher y close()
    private final ReentrantLock flushLock = new ReentrantLock();
    
    // Cola de commits SAFE pendientes de confirmación de durabilidad
    // Cada entrada contiene un CompletableFuture que se completa cuando el fsync cubre esa posición
    private final ConcurrentLinkedQueue<SafeCommitPromise> safeCommitPromises = new ConcurrentLinkedQueue<>();
    
    /**
     * Promesa de durabilidad para commits SAFE.
     * El flusher completa el future después de que channel.force() cubre esta entrada.
     */
    private static class SafeCommitPromise {
        final long transactionId;
        final CompletableFuture<Void> future;
        
        SafeCommitPromise(long transactionId, CompletableFuture<Void> future) {
            this.transactionId = transactionId;
            this.future = future;
        }
    }
    
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
        
        // Initialize non-blocking buffer and executor for async flush using ScheduledExecutorService for deterministic scheduling
        // ConcurrentLinkedQueue allows lock-free O(1) appends from multiple writers without blocking
        this.writeBuffer = new ConcurrentLinkedQueue<>();
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WAL-Flush-Thread");
            t.setDaemon(true);
            return t;
        });
        this.flushScheduled = new AtomicBoolean(false);
        
        // Start periodic flush thread with deterministic scheduling (no Thread.sleep jitter)
        startPeriodicFlush();
        
        logger.info("WAL initialized at {} (async flush enabled)", walPath.toAbsolutePath());
    }
    
    /**
     * Starts the periodic flush thread in background using ScheduledExecutorService.scheduleAtFixedRate
     * for deterministic scheduling without Thread.sleep jitter.
     * The flusher is the ONLY thread that writes to channel and calls channel.force().
     */
    private void startPeriodicFlush() {
        flushExecutor.scheduleAtFixedRate(() -> {
            if (!closed && pendingOperations.get() > 0) {
                doFlushCycle(false);
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        logger.debug("Periodic flush started with interval {}ms", FLUSH_INTERVAL_MS);
    }
    
    /**
     * Crea o abre un WAL en la ruta especificada
     */
    public static Wal open(Path walPath) throws IOException {
        return new Wal(walPath);
    }
    
    /**
     * Writes an entry to the WAL in a non-blocking manner (O(1) append).
     * Writers NEVER block or call doFlush/channel.force().
     * Only enqueues the entry to the ConcurrentLinkedQueue for the flusher to process.
     */
    public void write(long transactionId, OperationType operation, 
                                   String tableName, String key, byte[] data) throws IOException {
        checkClosed();
        
        long timestamp = System.currentTimeMillis();
        WalEntry entry = new WalEntry(timestamp, transactionId, operation, tableName, key, data);
        
        // Lock-free O(1) append - writers never block
        writeBuffer.offer(entry);
        pendingOperations.incrementAndGet();
        
        logger.trace("WAL entry enqueued: tx={}, op={}, table={}, key={}", 
                    transactionId, operation, tableName, key);
    }
    
    /**
     * Flush cycle executed by the flusher thread only.
     * Drains all entries from the buffer, writes them to channel, and performs a single fsync.
     * For SAFE commits, completes the associated CompletableFuture after fsync.
     * 
     * @param forceSyncIfSafe if true and there are SAFE commit promises, forces immediate fsync
     */
    private void doFlushCycle(boolean forceSyncIfSafe) {
        // Only the flusher thread executes this - no synchronization needed with writers
        // Writers use ConcurrentLinkedQueue which is lock-free
        
        // Acquire flushLock to serialize with other flush cycles (e.g., forced flushes for SAFE)
        // This lock is NEVER held by writers, only by flusher and close()/flush()
        flushLock.lock();
        try {
            if (writeBuffer.isEmpty() && pendingOperations.get() == 0) {
                return;
            }
            
            // Drain all entries from the buffer (ConcurrentLinkedQueue.drainTo is not available, so we iterate)
            List<WalEntry> batch = new ArrayList<>();
            WalEntry entry;
            while ((entry = writeBuffer.poll()) != null) {
                batch.add(entry);
            }
            
            if (batch.isEmpty()) {
                return;
            }
            
            // Write all entries to channel
            for (WalEntry e : batch) {
                ByteBuffer buffer = serializeEntry(e);
                channel.position(position);
                channel.write(buffer);
                position += buffer.limit();
            }
            
            // Single fsync for the entire batch
            channel.force(false);
            pendingOperations.set(Math.max(0, pendingOperations.get() - batch.size()));
            
            // Complete all SAFE commit promises that were included in this flush
            // All commits enqueued before this fsync are now durable
            SafeCommitPromise promise;
            while ((promise = safeCommitPromises.poll()) != null) {
                promise.future.complete(null);
            }
            
            logger.debug("Flushed {} entries to WAL (total pending: {})", batch.size(), pendingOperations.get());
            
        } catch (IOException e) {
            logger.error("Error in flush cycle: {}", e.getMessage());
            // Fail any pending SAFE promises
            SafeCommitPromise promise;
            while ((promise = safeCommitPromises.poll()) != null) {
                promise.future.completeExceptionally(e);
            }
        } finally {
            flushLock.unlock();
        }
    }
    
    /**
     * Writes a COMMIT entry with optional durability guarantee via CompletableFuture.
     * For SAFE mode: registers a promise and waits for fsync confirmation.
     * For NORMAL/ASYNC mode: just enqueues the COMMIT entry and returns immediately.
     * 
     * @param transactionId the transaction ID
     * @param forceSync if true (SAFE), wait for fsync confirmation; if false (NORMAL/ASYNC), return immediately
     * @throws IOException if an I/O error occurs during enqueue (not during fsync wait)
     */
    public void writeCommit(long transactionId, boolean forceSync) throws IOException {
        checkClosed();
        
        // Enqueue COMMIT entry (non-blocking O(1))
        long timestamp = System.currentTimeMillis();
        WalEntry commitEntry = new WalEntry(timestamp, transactionId, OperationType.COMMIT, "", "", new byte[0]);
        writeBuffer.offer(commitEntry);
        pendingOperations.incrementAndGet();
        
        if (forceSync) {
            // SAFE mode: register a promise and wait for fsync confirmation
            CompletableFuture<Void> promise = new CompletableFuture<>();
            safeCommitPromises.offer(new SafeCommitPromise(transactionId, promise));
            
            // Signal flusher to run immediately (by scheduling with 0 delay)
            flushExecutor.execute(() -> doFlushCycle(true));
            
            // Wait for fsync confirmation (blocks ONLY this commit thread, not other writers)
            try {
                promise.get(30, TimeUnit.SECONDS); // Timeout to avoid infinite hang
                logger.info("Transaction {} committed to WAL (fsync confirmed)", transactionId);
            } catch (Exception e) {
                logger.error("Transaction {} commit failed waiting for fsync: {}", transactionId, e.getMessage());
                throw new IOException("SAFE commit failed", e);
            }
        } else {
            // NORMAL/ASYNC mode: durability is eventual via periodic flush
            logger.debug("Transaction {} committed to WAL (buffered for group commit)", transactionId);
        }
    }
    
    /**
     * Writes a COMMIT entry with immediate fsync for backward compatibility.
     * Delegates to writeCommit(transactionId, true) to preserve previous behavior.
     * @param transactionId the transaction ID
     * @throws IOException if an I/O error occurs
     */
    public void writeCommit(long transactionId) throws IOException {
        writeCommit(transactionId, true);
    }
    
    /**
     * Writes a ROLLBACK entry (non-blocking O(1) append).
     * @param transactionId the transaction ID
     * @throws IOException if an I/O error occurs during enqueue
     */
    public void writeRollback(long transactionId) throws IOException {
        checkClosed();
        long timestamp = System.currentTimeMillis();
        WalEntry entry = new WalEntry(timestamp, transactionId, OperationType.ROLLBACK, "", "", new byte[0]);
        writeBuffer.offer(entry);
        pendingOperations.incrementAndGet();
    }
    
    /**
     * Writes a CHECKPOINT entry (non-blocking O(1) append).
     * @param transactionId the transaction ID
     * @throws IOException if an I/O error occurs during enqueue
     */
    public void writeCheckpoint(long transactionId) throws IOException {
        checkClosed();
        long timestamp = System.currentTimeMillis();
        WalEntry entry = new WalEntry(timestamp, transactionId, OperationType.CHECKPOINT, "", "", new byte[0]);
        writeBuffer.offer(entry);
        pendingOperations.incrementAndGet();
    }
    
    /**
     * Reads all entries from the WAL from the beginning.
     * Note: This is used for recovery and should only be called when there are no active writers.
     */
    public List<WalEntry> readAll() throws IOException {
        checkClosed();
        
        List<WalEntry> entries = new ArrayList<>();
        channel.position(HEADER_SIZE); // Skip header
        
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
                
                if (entryLength <= 0 || entryLength > 10 * 1024 * 1024) { // Max 10MB per entry
                    logger.warn("Invalid WAL entry length: {}", entryLength);
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
                    break; // Stop replay on corruption
                }
                
            } catch (Exception e) {
                logger.warn("Error reading WAL entry, possible corruption: {}", e.getMessage());
                break;
            }
        }
        
        logger.debug("Read {} entries from WAL", entries.size());
        return entries;
    }
    
    /**
     * Truncates the WAL (after a successful checkpoint).
     * Waits for the buffer to drain completely before truncating.
     */
    public void truncate() throws IOException {
        checkClosed();
        // Wait for buffer to be empty by forcing a flush cycle and waiting
        flushLock.lock();
        try {
            // Drain all entries and fsync before truncating
            doFlushCycle(false);
            channel.truncate(0);
            position = 0;
            writeHeader();
            logger.info("WAL truncated");
        } finally {
            flushLock.unlock();
        }
    }
    
    /**
     * Closes the WAL, performing a final flush of all pending operations.
     * Cancels any scheduled tasks and ensures durability with a final fsync before closing the channel.
     * Completes or fails any pending SAFE commit promises.
     */
    public void close() throws IOException {
        if (!closed) {
            closed = true; // Stop accepting new writes
            
            // Shutdown the executor, canceling periodic flushes
            if (flushExecutor != null && !flushExecutor.isShutdown()) {
                flushExecutor.shutdown();
                try {
                    if (!flushExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        flushExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    flushExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // Final flush: acquire flushLock and drain everything
            flushLock.lock();
            try {
                doFlushCycle(false);
                
                // Complete any remaining SAFE promises (they won't get fsync'd individually but are in the final force)
                SafeCommitPromise promise;
                while ((promise = safeCommitPromises.poll()) != null) {
                    promise.future.complete(null);
                }
                
                channel.force(true); // Ensure all data is on disk
                channel.close();
                logger.info("WAL closed after flushing {} pending operations", pendingOperations.get());
            } finally {
                flushLock.unlock();
            }
        }
    }
    
    /**
     * Forces an immediate flush of all buffered entries.
     * Used for explicit synchronization points.
     */
    public void flush() throws IOException {
        flushLock.lock();
        try {
            doFlushCycle(false);
        } finally {
            flushLock.unlock();
        }
    }
    
    /**
     * Checks if the WAL is closed
     */
    public boolean isClosed() {
        return closed;
    }
    
    /**
     * Gets the number of entries in the WAL (reads all entries - expensive operation).
     * Note: This is mainly for debugging/testing and should not be used in production code paths.
     */
    public int size() throws IOException {
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
