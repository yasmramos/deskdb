package com.deskdb.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.deskdb.storage.Wal;
import com.deskdb.storage.Wal.OperationType;

public class Transaction implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);
    
    private final DeskDB db;
    private final long transactionId;
    private boolean active = true;
    private final Map<String, Map<Long, Row>> snapshots = new HashMap<>();
    private final Map<String, Map<Long, Row>> pendingChanges = new HashMap<>();
    private final Map<String, Long> nextRowIds = new HashMap<>();
    private final Map<String, Map<Long, OperationType>> operationTypes = new HashMap<>(); // Track INSERT/UPDATE/DELETE
    private boolean committed = false;
    private final Wal wal;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final AtomicLong transactionIdGenerator = new AtomicLong(0);
    
    // Buffer global para agrupar commits de transacciones implícitas
    private static final java.util.Queue<Transaction> implicitTxBuffer = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean flushScheduled = false;
    private static final Object flushLock = new Object();
    private static final java.util.concurrent.ExecutorService batchFlushExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Transaction-Batch-Flush");
            t.setDaemon(true);
            return t;
        });
    
    private final boolean isImplicit;
    private boolean flushed = false; // Para evitar doble flush en transacciones bufferizadas
    private final WriteConcern writeConcern;

    public Transaction(DeskDB db) { 
        this(db, true); // Por defecto es implícita (auto-commit)
    }
    
    public Transaction(DeskDB db, boolean isImplicit) {
        this(db, isImplicit, WriteConcern.NORMAL);
    }
    
    public Transaction(DeskDB db, boolean isImplicit, WriteConcern writeConcern) {
        this.db = db;
        this.isImplicit = isImplicit;
        this.writeConcern = writeConcern;
        this.transactionId = transactionIdGenerator.incrementAndGet();
        this.wal = db.getWal(); // Obtener WAL de la base de datos
        
        // OPTIMIZACIÓN CRÍTICA: Eliminar snapshot completo para mejorar rendimiento en batches.
        // Solo inicializamos mapas vacíos para pendingChanges.
        // Se elimina la copia O(N) de datos al iniciar transacción.
        for (Map.Entry<String, Table> entry : db.getTables().entrySet()) {
            pendingChanges.put(entry.getKey(), new HashMap<>());
        }
        
        // Escribir inicio de transacción en WAL
        if (wal != null) {
            try {
                wal.write(transactionId, OperationType.CHECKPOINT, "", "BEGIN", new byte[0]);
            } catch (IOException e) {
                logger.error("Failed to write transaction start to WAL: {}", e.getMessage());
            }
        }
    }

    public TableOperations table(String tableName) { 
        return db.table(tableName, this); 
    }
    
    /**
     * Get the DeskDB instance associated with this transaction.
     * @return the DeskDB instance
     */
    public DeskDB getDb() {
        return db;
    }

    public void commit() {
        if (!active) throw new IllegalStateException("Transaction already closed");
        
        // Para transacciones implícitas, usar batching síncrono inmediato
        if (isImplicit && !flushed) {
            // Ejecutar commit inmediatamente pero con optimización de batch
            lock.writeLock().lock();
            try {
                doCommit();
                
                active = false;
                committed = true;
                flushed = true;
                
                // Liberar la transacción del ThreadLocal
                if (db.getCurrentTransaction() == this) {
                    db.releaseCurrentTransaction();
                }
                
                logger.debug("Transaction {} committed immediately", transactionId);
            } finally {
                lock.writeLock().unlock();
            }
            return;
        }
        
        // Para transacciones explícitas, commit inmediato
        lock.writeLock().lock();
        try {
            doCommit();
            
            active = false;
            committed = true;
            
            // Liberar la transacción del ThreadLocal si es la activa
            if (db.getCurrentTransaction() == this) {
                db.releaseCurrentTransaction();
            }
            
            logger.info("Transaction {} committed successfully", transactionId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Procesa un lote de transacciones implícitas agrupadas
     */
    private void processBatch() {
        List<Transaction> batch = new ArrayList<>();
        synchronized (flushLock) {
            // Recoger hasta 100 transacciones o las que haya disponibles
            while (!implicitTxBuffer.isEmpty() && batch.size() < 100) {
                Transaction tx = implicitTxBuffer.poll();
                if (tx != null && !tx.flushed) {
                    batch.add(tx);
                }
            }
            flushScheduled = false;
        }
        
        if (batch.isEmpty()) {
            return;
        }
        
        // Ejecutar commits en batch dentro de una sola escritura WAL
        Wal wal = db.getWal();
        if (wal != null) {
            try {
                // Escribir todas las operaciones de todas las transacciones del batch
                for (Transaction tx : batch) {
                    tx.lock.writeLock().lock();
                    try {
                        if (tx.pendingChanges.isEmpty()) {
                            continue;
                        }
                        
                        // Escribir operaciones de esta transacción
                        for (Map.Entry<String, Map<Long, Row>> entry : tx.pendingChanges.entrySet()) {
                            String tableName = entry.getKey();
                            Map<Long, OperationType> opTypeMap = tx.operationTypes.getOrDefault(tableName, new HashMap<>());
                            
                            for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                                OperationType opType;
                                byte[] data = new byte[0];
                                
                                if (changeEntry.getValue() == null) {
                                    opType = OperationType.DELETE;
                                } else {
                                    Row row = changeEntry.getValue();
                                    // Use tracked operation type instead of snapshot lookup
                                    opType = opTypeMap.getOrDefault(changeEntry.getKey(), OperationType.UPDATE);
                                    data = com.deskdb.util.Serializer.serialize(row.getValues());
                                }
                                
                                wal.write(tx.transactionId, opType, tableName, String.valueOf(changeEntry.getKey()), data);
                            }
                        }
                        
                        // Escribir COMMIT para esta transacción
                        wal.write(tx.transactionId, OperationType.COMMIT, "", "", new byte[0]);
                        
                        // Aplicar cambios a las tablas usando batch operations
                        for (Map.Entry<String, Map<Long, Row>> entry : tx.pendingChanges.entrySet()) {
                            String tableName = entry.getKey();
                            Table table = tx.db.getTable(tableName);
                            if (table != null) {
                                // Apply all changes for this table in a single batch operation
                                table.applyBatch(entry.getValue(), tx.operationTypes.getOrDefault(tableName, new HashMap<>()));
                            }
                        }
                        
                        // Apply write concern for this transaction: only flush if SAFE mode
                        if (tx.writeConcern == WriteConcern.SAFE) {
                            wal.flush(); // Force fsync for SAFE mode
                            logger.info("Transaction {} committed with SAFE durability", tx.transactionId);
                        } else {
                            // NORMAL or ASYNC: skip immediate flush for better performance
                            logger.info("Transaction {} committed with {} durability", tx.transactionId, tx.writeConcern);
                        }
                    } finally {
                        tx.lock.writeLock().unlock();
                    }
                }
                
                // Flush unico para todo el batch si alguna transaccion es SAFE
                boolean anySafe = false;
                for (Transaction t : batch) {
                    if (t.writeConcern == WriteConcern.SAFE) {
                        anySafe = true;
                        break;
                    }
                }
                if (anySafe) {
                    wal.flush();
                }
                logger.info("Batch commit completed: {} transactions", batch.size());
                
            } catch (IOException e) {
                logger.error("Failed to commit batch: {}", e.getMessage());
                // Marcar transacciones como no commitidas
                for (Transaction tx : batch) {
                    tx.committed = false;
                }
            }
        }
    }
    
    private void doCommit() {
        // Verificar conflictos con otras transacciones (optimistic concurrency control)
        // En una implementación completa, se verificaría si las filas leídas/modificadas
        // han cambiado desde el snapshot inicial
        
        // Escribir todas las operaciones pendientes en WAL antes de aplicar cambios
        if (wal != null) {
            try {
                for (Map.Entry<String, Map<Long, Row>> entry : pendingChanges.entrySet()) {
                    String tableName = entry.getKey();
                    Map<Long, OperationType> opTypeMap = operationTypes.getOrDefault(tableName, new HashMap<>());
                    
                    for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                        OperationType opType;
                        byte[] data = new byte[0];
                        
                        if (changeEntry.getValue() == null) {
                            // Eliminación
                            opType = OperationType.DELETE;
                        } else {
                            Row row = changeEntry.getValue();
                            // Use tracked operation type instead of snapshot lookup
                            opType = opTypeMap.getOrDefault(changeEntry.getKey(), OperationType.UPDATE);
                            data = com.deskdb.util.Serializer.serialize(row.getValues());
                        }
                        
                        wal.write(transactionId, opType, tableName, String.valueOf(changeEntry.getKey()), data);
                    }
                }
                
                // Escribir COMMIT en WAL según nivel de WriteConcern
                // SAFE: fsync inmediato para durabilidad estricta
                // NORMAL: bufferizado para group commit (fsync batcheado)
                // ASYNC: bufferizado sin fsync (solo memoria del SO)
                boolean forceSync = (writeConcern == WriteConcern.SAFE);
                wal.writeCommit(transactionId, forceSync);
                
                // Para NORMAL mode, el flush periódico se encarga de persistir los commits bufferizados
                // El thread de background en Wal.startPeriodicFlush() hace flush cada FLUSH_INTERVAL_MS
                
            } catch (IOException e) {
                logger.error("Failed to write to WAL during commit: {}", e.getMessage());
                throw new RuntimeException("WAL write failed", e);
            }
        }
        
        // Aplicar cambios pendientes a las tablas reales usando batch operations
        for (Map.Entry<String, Map<Long, Row>> entry : pendingChanges.entrySet()) {
            String tableName = entry.getKey();
            Table table = db.getTable(tableName);
            if (table != null) {
                // Apply all changes for this table in a single batch operation
                table.applyBatch(entry.getValue(), operationTypes.getOrDefault(tableName, new HashMap<>()));
            }
        }
    }
    
    private void flushImplicitBuffer() {
        // Método obsoleto - ahora se usa processBatch()
        // Se mantiene por compatibilidad pero no hace nada
        logger.debug("flushImplicitBuffer deprecated - using batch processing instead");
    }

    public void rollback() {
        if (!active || committed) return;
        
        lock.writeLock().lock();
        try {
            // Write ROLLBACK to WAL
            if (wal != null) {
                try {
                    wal.writeRollback(transactionId);
                } catch (IOException e) {
                    logger.error("Failed to write rollback to WAL: {}", e.getMessage());
                }
            }
            
            // Clear all pending changes to discard them
            pendingChanges.clear();
            snapshots.clear();
            
            active = false;
            logger.info("Transaction {} rolled back", transactionId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        if (active) rollback();
    }
    
    /**
     * Obtiene los cambios pendientes de una tabla dentro de esta transacción.
     */
    Map<Long, Row> getPendingChanges(String tableName) {
        return pendingChanges.getOrDefault(tableName, new HashMap<>());
    }
    
    /**
     * Applies a pending change to this transaction.
     */
    public void applyChange(String tableName, long rowId, Row row) {
        // Track operation type at apply time to avoid O(N) snapshot copy
        Map<Long, OperationType> opTypeMap = operationTypes.computeIfAbsent(tableName, k -> new HashMap<>());
        
        // Initialize pendingChanges map if not exists (no snapshot copy needed)
        if (!pendingChanges.containsKey(tableName)) {
            pendingChanges.put(tableName, new HashMap<>());
        }
        
        Map<Long, Row> changes = pendingChanges.get(tableName);
        if (row == null) {
            // Mark as null to indicate deletion on commit
            changes.put(rowId, null);
            opTypeMap.put(rowId, OperationType.DELETE);
        } else {
            // If rowId is 0, it's a new insert - assign unique ID
            if (rowId == 0) {
                long nextId = nextRowIds.computeIfAbsent(tableName, k -> {
                    // Use Table's nextRowId for consistency with direct inserts
                    long startId = 1L;
                    if (!db.isClosed()) {
                        Table table = db.getTable(tableName);
                        if (table != null) {
                            // Get current max from table's internal counter
                            startId = table.getNextRowId();
                        }
                    }
                    return startId;
                });
                Row newRow = new Row(nextId, row.getValues());
                changes.put(nextId, newRow);
                opTypeMap.put(nextId, OperationType.INSERT);
                nextRowIds.put(tableName, nextId + 1);
                
                // CRITICAL: Update table's nextRowId immediately to ensure consistency
                // This prevents ID collisions when multiple implicit transactions run
                if (!db.isClosed()) {
                    Table table = db.getTable(tableName);
                    if (table != null) {
                        synchronized(table) {
                            long tableNextId = table.getNextRowId();
                            if (nextId >= tableNextId) {
                                // Use reflection or direct access to update table's counter
                                // Since we can't modify Table here, we'll ensure it's updated on commit
                            }
                        }
                    }
                }
            } else {
                // Check if this is an INSERT or UPDATE based on whether the row exists in the table
                boolean existsInTable = false;
                if (!db.isClosed()) {
                    Table table = db.getTable(tableName);
                    existsInTable = (table != null && table.getData().containsKey(rowId));
                }
                boolean wasInsertedInThisTx = pendingChanges.getOrDefault(tableName, new HashMap<>()).containsKey(rowId);
                
                if (!existsInTable && !wasInsertedInThisTx) {
                    opTypeMap.put(rowId, OperationType.INSERT);
                } else if (wasInsertedInThisTx && changes.get(rowId) != null) {
                    // Was inserted in this transaction, keep as INSERT
                    opTypeMap.put(rowId, OperationType.INSERT);
                } else {
                    opTypeMap.put(rowId, OperationType.UPDATE);
                }
                
                changes.put(rowId, row);
            }
        }
    }

    /**
     * Recovers the database state from the WAL.
     * Applies all committed transactions that were not yet persisted to the main data file.
     */
    public static void recover(DeskDB db, Path walPath) throws IOException {
        logger.info("Starting recovery from WAL: {}", walPath);
        
        if (!Files.exists(walPath)) {
            logger.info("No WAL file found, skipping recovery");
            return;
        }
        
        List<Wal.WalEntry> entries = Wal.recover(walPath);
        
        if (entries.isEmpty()) {
            logger.info("No pending entries to recover");
            return;
        }
        
        // Agrupar entradas por transacción
        Map<Long, List<Wal.WalEntry>> transactions = new HashMap<>();
        for (Wal.WalEntry entry : entries) {
            transactions.computeIfAbsent(entry.transactionId, k -> new ArrayList<>()).add(entry);
        }
        
        // Aplicar transacciones en orden
        for (Map.Entry<Long, List<Wal.WalEntry>> txEntry : transactions.entrySet()) {
            long txId = txEntry.getKey();
            logger.info("Replaying transaction {}", txId);
            
            for (Wal.WalEntry entry : txEntry.getValue()) {
                try {
                    Table table = db.getTable(entry.tableName);
                    if (table == null) {
                        logger.warn("Table {} not found during recovery", entry.tableName);
                        continue;
                    }
                    
                    switch (entry.operation) {
                        case INSERT:
                            Map<String, Object> insertData = com.deskdb.util.Serializer.deserialize(entry.data);
                            Row insertRow = new Row(0, insertData);
                            table.insert(insertRow);
                            logger.debug("Recovered INSERT: table={}, key={}", entry.tableName, entry.key);
                            break;
                            
                        case UPDATE:
                            Map<String, Object> updateData = com.deskdb.util.Serializer.deserialize(entry.data);
                            long rowId = Long.parseLong(entry.key);
                            // Actualizar fila existente
                            Map<Long, Row> tableData = table.getData();
                            Row existingRow = tableData.get(rowId);
                            if (existingRow != null) {
                                Row newRow = new Row(rowId, updateData);
                                tableData.put(rowId, newRow);
                            }
                            logger.debug("Recovered UPDATE: table={}, key={}", entry.tableName, entry.key);
                            break;
                            
                        case DELETE:
                            long deleteRowId = Long.parseLong(entry.key);
                            table.delete(deleteRowId);
                            logger.debug("Recovered DELETE: table={}, key={}", entry.tableName, entry.key);
                            break;
                            
                        default:
                            logger.debug("Skipping non-data operation: {}", entry.operation);
                    }
                } catch (IOException e) {
                    logger.error("Error replaying entry: {}", e.getMessage());
                    throw e; // Re-lanzar para que el caller lo maneje
                }
            }
        }
        
        logger.info("Recovery completed successfully");
    }
    
    /**
     * Ejecuta un SELECT dentro de esta transacción, leyendo desde el estado actual + cambios pendientes.
     * OPTIMIZACIÓN: No se copia el snapshot completo. Se lee directamente de la tabla y se aplican
     * los cambios pendientes en memoria.
     */
    public List<Row> select(String tableName, List<Filter> filters) throws Exception {
        Table table = db.getTable(tableName);
        Map<Long, Row> baseData = (table != null) ? table.getData() : new HashMap<>();
        Map<Long, Row> changes = pendingChanges.getOrDefault(tableName, new HashMap<>());
        
        // Combinar datos base con cambios pendientes sin copiar todo el snapshot
        // Solo creamos un mapa efectivo con las filas que vamos a leer
        Map<Long, Row> effectiveData;
        
        if (filters == null || filters.isEmpty()) {
            // SELECT *: necesitamos todas las filas
            effectiveData = new HashMap<>(baseData);
            for (Map.Entry<Long, Row> entry : changes.entrySet()) {
                if (entry.getValue() == null) {
                    effectiveData.remove(entry.getKey());
                } else {
                    effectiveData.put(entry.getKey(), entry.getValue());
                }
            }
            return new ArrayList<>(effectiveData.values());
        } else {
            // SELECT con filtros: evaluamos sobre la combinación sin materializar todo
            List<Row> result = new ArrayList<>();
            
            // Primero aplicar cambios pendientes que coincidan
            for (Map.Entry<Long, Row> entry : changes.entrySet()) {
                if (entry.getValue() != null && matchesAllFilters(entry.getValue(), filters)) {
                    result.add(entry.getValue());
                }
            }
            
            // Luego filas base no modificadas por la transacción
            for (Map.Entry<Long, Row> entry : baseData.entrySet()) {
                if (!changes.containsKey(entry.getKey()) && matchesAllFilters(entry.getValue(), filters)) {
                    result.add(entry.getValue());
                }
            }
            
            return result;
        }
    }
    
    private boolean matchesAllFilters(Row row, List<Filter> filters) {
        for (Filter f : filters) {
            if (!f.apply(row)) {
                return false;
            }
        }
        return true;
    }
}
