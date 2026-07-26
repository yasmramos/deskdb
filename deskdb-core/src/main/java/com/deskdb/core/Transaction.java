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

    public Transaction(DeskDB db) { 
        this(db, true); // Por defecto es implícita (auto-commit)
    }
    
    public Transaction(DeskDB db, boolean isImplicit) {
        this.db = db;
        this.isImplicit = isImplicit;
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
                            for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                                OperationType opType;
                                byte[] data = new byte[0];
                                
                                if (changeEntry.getValue() == null) {
                                    opType = OperationType.DELETE;
                                } else {
                                    Row row = changeEntry.getValue();
                                    Map<Long, Row> snapshot = tx.snapshots.getOrDefault(tableName, new HashMap<>());
                                    if (!snapshot.containsKey(changeEntry.getKey())) {
                                        opType = OperationType.INSERT;
                                        data = com.deskdb.util.Serializer.serialize(row.getValues());
                                    } else {
                                        opType = OperationType.UPDATE;
                                        data = com.deskdb.util.Serializer.serialize(row.getValues());
                                    }
                                }
                                
                                wal.write(tx.transactionId, opType, tableName, String.valueOf(changeEntry.getKey()), data);
                            }
                        }
                        
                        // Escribir COMMIT para esta transacción
                        wal.write(tx.transactionId, OperationType.COMMIT, "", "", new byte[0]);
                        
                        // Aplicar cambios a las tablas
                        for (Map.Entry<String, Map<Long, Row>> entry : tx.pendingChanges.entrySet()) {
                            String tableName = entry.getKey();
                            Table table = tx.db.getTable(tableName);
                            if (table != null) {
                                Map<Long, Row> tableData = table.getData();
                                for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                                    if (changeEntry.getValue() == null) {
                                        tableData.remove(changeEntry.getKey());
                                    } else {
                                        tableData.put(changeEntry.getKey(), changeEntry.getValue());
                                    }
                                }
                            }
                        }
                        
                        tx.committed = true;
                        logger.debug("Batch transaction {} committed", tx.transactionId);
                    } finally {
                        tx.lock.writeLock().unlock();
                    }
                }
                
                // Flush único para todo el batch
                wal.flush();
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
                    for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                        OperationType opType;
                        byte[] data = new byte[0];
                        
                        if (changeEntry.getValue() == null) {
                            // Eliminación
                            opType = OperationType.DELETE;
                        } else {
                            Row row = changeEntry.getValue();
                            Map<Long, Row> snapshot = snapshots.getOrDefault(tableName, new HashMap<>());
                            if (!snapshot.containsKey(changeEntry.getKey())) {
                                // Inserción
                                opType = OperationType.INSERT;
                                data = com.deskdb.util.Serializer.serialize(row.getValues());
                            } else {
                                // Actualización
                                opType = OperationType.UPDATE;
                                data = com.deskdb.util.Serializer.serialize(row.getValues());
                            }
                        }
                        
                        wal.write(transactionId, opType, tableName, String.valueOf(changeEntry.getKey()), data);
                    }
                }
                
                // Escribir COMMIT en WAL
                wal.writeCommit(transactionId);
            } catch (IOException e) {
                logger.error("Failed to write to WAL during commit: {}", e.getMessage());
                throw new RuntimeException("WAL write failed", e);
            }
        }
        
        // Aplicar cambios pendientes a las tablas reales
        for (Map.Entry<String, Map<Long, Row>> entry : pendingChanges.entrySet()) {
            String tableName = entry.getKey();
            Table table = db.getTable(tableName);
            if (table != null) {
                Map<Long, Row> tableData = table.getData();
                
                // Procesar todos los cambios
                for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                    if (changeEntry.getValue() == null) {
                        // Eliminación
                        tableData.remove(changeEntry.getKey());
                    } else {
                        // Inserción o actualización
                        tableData.put(changeEntry.getKey(), changeEntry.getValue());
                    }
                }
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
            // Escribir ROLLBACK en WAL
            if (wal != null) {
                try {
                    wal.writeRollback(transactionId);
                } catch (IOException e) {
                    logger.error("Failed to write rollback to WAL: {}", e.getMessage());
                }
            }
            
            active = false;
            // NO restaurar snapshots - los commits son atómicos y persistentes
            // El rollback solo descarta cambios pendientes de esta transacción
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
        // Initialize snapshot with current table data if not exists
        if (!snapshots.containsKey(tableName)) {
            Table table = db.getTable(tableName);
            if (table != null) {
                // Copy current table data to snapshot
                snapshots.put(tableName, new HashMap<>(table.getData()));
            } else {
                snapshots.put(tableName, new HashMap<>());
            }
        }
        
        Map<Long, Row> changes = pendingChanges.computeIfAbsent(tableName, k -> new HashMap<>());
        if (row == null) {
            // Mark as null to indicate deletion on commit
            changes.put(rowId, null);
        } else {
            // If rowId is 0, it's a new insert - assign unique ID
            if (rowId == 0) {
                long nextId = nextRowIds.computeIfAbsent(tableName, k -> {
                    // Start from current table size + 1 to avoid conflicts
                    Table table = db.getTable(tableName);
                    long startId = 1L;
                    if (table != null) {
                        startId = table.getData().size() + 1L;
                    }
                    return startId;
                });
                Row newRow = new Row(nextId, row.getValues());
                changes.put(nextId, newRow);
                nextRowIds.put(tableName, nextId + 1);
            } else {
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
                } catch (ClassNotFoundException e) {
                    logger.error("Class not found during recovery: {}", e.getMessage());
                    throw new IOException("Deserialization failed", e);
                }
            }
        }
        
        logger.info("Recovery completed successfully");
    }
    
    /**
     * Ejecuta un SELECT dentro de esta transacción, leyendo desde el snapshot + cambios pendientes.
     */
    public List<Row> select(String tableName, List<Filter> filters) throws Exception {
        // Para transacciones implícitas, inicializar snapshot si no existe
        if (isImplicit && !snapshots.containsKey(tableName)) {
            Table table = db.getTable(tableName);
            if (table != null) {
                snapshots.put(tableName, new HashMap<>(table.getData()));
            } else {
                snapshots.put(tableName, new HashMap<>());
            }
        }
        
        Map<Long, Row> snapshot = snapshots.getOrDefault(tableName, new HashMap<>());
        Map<Long, Row> changes = pendingChanges.getOrDefault(tableName, new HashMap<>());
        
        // Combinar snapshot con cambios pendientes
        Map<Long, Row> effectiveData = new HashMap<>(snapshot);
        
        // Aplicar todos los cambios (ya tienen IDs únicos asignados en applyChange)
        for (Map.Entry<Long, Row> entry : changes.entrySet()) {
            if (entry.getValue() == null) {
                // Eliminación
                effectiveData.remove(entry.getKey());
            } else {
                // Inserción o actualización
                effectiveData.put(entry.getKey(), entry.getValue());
            }
        }
        
        // Aplicar filtros
        List<Row> result = new ArrayList<>();
        for (Row row : effectiveData.values()) {
            boolean matches = true;
            if (filters != null) {
                for (Filter f : filters) {
                    if (!f.apply(row)) {
                        matches = false;
                        break;
                    }
                }
            }
            if (matches) {
                result.add(row);
            }
        }
        return result;
    }
}
