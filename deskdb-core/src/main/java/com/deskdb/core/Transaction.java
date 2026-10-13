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

    // NOTE: A previous "group commit" implementation used a static queue
    // (implicitTxBuffer) plus a shared background flusher thread. That code was
    // dead (nothing ever enqueued into the buffer) and dangerous: being static,
    // it leaked state across DeskDB instances and between tests running in the
    // same JVM. It has been removed. Implicit transactions now commit
    // synchronously in commit(), which is correct and deterministic.

    private final boolean isImplicit;
    private boolean flushed = false; // Prevents double flush on buffered transactions
    private final WriteConcern writeConcern;

    public Transaction(DeskDB db) { 
        this(db, true); // Implicit (auto-commit) by default
    }
    
    public Transaction(DeskDB db, boolean isImplicit) {
        this(db, isImplicit, WriteConcern.NORMAL);
    }
    
    public Transaction(DeskDB db, boolean isImplicit, WriteConcern writeConcern) {
        this.db = db;
        this.isImplicit = isImplicit;
        this.writeConcern = writeConcern;
        this.transactionId = transactionIdGenerator.incrementAndGet();
        this.wal = db.getWal(); // Obtain the database's WAL (may be null in in-memory mode)
        
        // CRITICAL OPTIMIZATION: removed the full snapshot copy to improve batch performance.
        // Only initialize empty maps for pendingChanges.
        // The O(N) data copy at transaction start is eliminated.
        for (Map.Entry<String, Table> entry : db.getTables().entrySet()) {
            pendingChanges.put(entry.getKey(), new HashMap<>());
        }
        
        // Write transaction start record to the WAL
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

        // All transactions (implicit or explicit) commit synchronously here.
        // The "flushed" flag only guards against a double flush left over from
        // the removed group-commit buffer; it is false unless this transaction
        // was already committed, which the "active" check above prevents.
        lock.writeLock().lock();
        try {
            doCommit();

            active = false;
            committed = true;
            flushed = true;

            // Release the transaction from the ThreadLocal if it is the active one
            if (db.getCurrentTransaction() == this) {
                db.releaseCurrentTransaction();
            }

            logger.info("Transaction {} committed successfully", transactionId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void doCommit() {
        // Check conflicts with other transactions (optimistic concurrency control)
        // In a complete implementation, this would verify whether the rows read/modified
        // have changed since the initial snapshot
        
        // Write all pending operations to the WAL before applying changes
        if (wal != null) {
            try {
                for (Map.Entry<String, Map<Long, Row>> entry : pendingChanges.entrySet()) {
                    String tableName = entry.getKey();
                    Map<Long, OperationType> opTypeMap = operationTypes.getOrDefault(tableName, new HashMap<>());
                    
                    for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                        OperationType opType;
                        byte[] data = new byte[0];
                        
                        if (changeEntry.getValue() == null) {
                            // Deletion
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
                
                // Write COMMIT to the WAL according to the WriteConcern level
                // SAFE: immediate fsync for strict durability
                // NORMAL: buffered for group commit (batched fsync)
                // ASYNC: buffered without fsync (OS memory only)
                boolean forceSync = (writeConcern == WriteConcern.SAFE);
                wal.writeCommit(transactionId, forceSync);
                
                // In NORMAL mode, the periodic flush persists the buffered commits
                // The background thread in Wal.startPeriodicFlush() flushes every FLUSH_INTERVAL_MS
                
            } catch (IOException e) {
                logger.error("Failed to write to WAL during commit: {}", e.getMessage());
                throw new RuntimeException("WAL write failed", e);
            }
        }
        
        // Apply pending changes to the real tables using batch operations.
        // Deterministic order by table name: HashMap iteration order is not stable,
        // which made MVCC version chains and assigned row ids vary across
        // executions with the same operation script.
        List<String> sortedTableNames = new ArrayList<>(pendingChanges.keySet());
        java.util.Collections.sort(sortedTableNames);
        for (String tableName : sortedTableNames) {
            Map<Long, Row> tableChanges = pendingChanges.get(tableName);
            if (tableChanges == null || tableChanges.isEmpty()) {
                continue; // nothing to apply for this table
            }
            Table table = db.getTable(tableName);
            if (table != null) {
                // Apply all changes for this table in a single batch operation
                table.applyBatch(tableChanges, operationTypes.getOrDefault(tableName, new HashMap<>()));
            }
        }
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
     * Gets the pending changes for a table within this transaction.
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
            pendingChanges.put(tableName, new java.util.TreeMap<>());
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
                
                // CRITICAL: Reserve the id in the table's counter immediately so
                // concurrent direct inserts (Table.insert) never hand out a rowId
                // already claimed by this pending transactional insert.
                if (!db.isClosed()) {
                    Table table = db.getTable(tableName);
                    if (table != null) {
                        table.ensureNextRowIdAtLeast(nextId + 1);
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
        
        // Group entries by transaction
        Map<Long, List<Wal.WalEntry>> transactions = new HashMap<>();
        for (Wal.WalEntry entry : entries) {
            transactions.computeIfAbsent(entry.transactionId, k -> new ArrayList<>()).add(entry);
        }
        
        // Apply transactions in order
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
                            // Update existing row
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
                    throw e; // Rethrow so the caller can handle it
                }
            }
        }
        
        logger.info("Recovery completed successfully");
    }
    
    /**
     * Executes a SELECT within this transaction, reading from the current state plus pending changes.
     * OPTIMIZATION: the full snapshot is not copied. Rows are read directly from the table and
     * pending in-memory changes are applied on top.
     */
    public List<Row> select(String tableName, List<Filter> filters) throws Exception {
        Table table = db.getTable(tableName);
        Map<Long, Row> baseData = (table != null) ? table.getData() : new HashMap<>();
        Map<Long, Row> changes = pendingChanges.getOrDefault(tableName, new HashMap<>());
        
        // Combine base data with pending changes without copying the whole snapshot
        // Only build an effective map with the rows we are going to read
        Map<Long, Row> effectiveData;
        
        if (filters == null || filters.isEmpty()) {
            // SELECT *: we need all rows
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
            // Filtered SELECT: evaluate over the combination without materializing everything
            List<Row> result = new ArrayList<>();
            
            // First apply matching pending changes
            for (Map.Entry<Long, Row> entry : changes.entrySet()) {
                if (entry.getValue() != null && matchesAllFilters(entry.getValue(), filters)) {
                    result.add(entry.getValue());
                }
            }
            
            // Then base rows not modified by the transaction
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
