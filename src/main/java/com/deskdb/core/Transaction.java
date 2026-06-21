package com.deskdb.core;

import com.deskdb.storage.Wal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestiona transacciones ACID en DeskDB usando Write-Ahead Log (WAL).
 * 
 * Soporta:
 * - BEGIN, COMMIT, ROLLBACK
 * - Aislamiento básico (lecturas ven datos confirmados)
 * - Durabilidad mediante WAL
 */
public class Transaction implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);
    private static final AtomicLong transactionIdGenerator = new AtomicLong(0);
    
    private final DeskDB db;
    private final long transactionId;
    private final Wal wal;
    private final Path walPath;
    private boolean active = true;
    private boolean committed = false;
    private boolean rolledBack = false;
    
    // Operaciones pendientes en esta transacción
    private final List<PendingOperation> pendingOperations = new ArrayList<>();
    
    /**
     * Tipos de operaciones pendientes
     */
    enum OperationType {
        INSERT, UPDATE, DELETE
    }
    
    /**
     * Operación pendiente de ser confirmada
     */
    static class PendingOperation {
        final OperationType type;
        final String tableName;
        final String key;
        final Map<String, Object> data;
        
        PendingOperation(OperationType type, String tableName, String key, Map<String, Object> data) {
            this.type = type;
            this.tableName = tableName;
            this.key = key;
            this.data = data;
        }
    }
    
    Transaction(DeskDB db, Path walPath) throws IOException {
        this.db = db;
        this.transactionId = transactionIdGenerator.incrementAndGet();
        this.walPath = walPath;
        this.wal = Wal.open(walPath);
        
        logger.info("Transaction {} started", transactionId);
    }
    
    /**
     * Obtiene el ID de esta transacción
     */
    public long getTransactionId() {
        return transactionId;
    }
    
    /**
     * Verifica si la transacción está activa
     */
    public boolean isActive() {
        return active && !committed && !rolledBack;
    }
    
    /**
     * Registra una operación de inserción pendiente
     */
    void registerInsert(String tableName, String key, Map<String, Object> data) throws IOException {
        checkActive();
        
        byte[] serializedData = serializeMap(data);
        wal.write(transactionId, Wal.OperationType.INSERT, tableName, key, serializedData);
        
        pendingOperations.add(new PendingOperation(OperationType.INSERT, tableName, key, data));
        
        logger.trace("Transaction {} registered INSERT: table={}, key={}", transactionId, tableName, key);
    }
    
    /**
     * Registra una operación de actualización pendiente
     */
    void registerUpdate(String tableName, String key, Map<String, Object> data) throws IOException {
        checkActive();
        
        byte[] serializedData = serializeMap(data);
        wal.write(transactionId, Wal.OperationType.UPDATE, tableName, key, serializedData);
        
        pendingOperations.add(new PendingOperation(OperationType.UPDATE, tableName, key, data));
        
        logger.trace("Transaction {} registered UPDATE: table={}, key={}", transactionId, tableName, key);
    }
    
    /**
     * Registra una operación de eliminación pendiente
     */
    void registerDelete(String tableName, String key) throws IOException {
        checkActive();
        
        wal.write(transactionId, Wal.OperationType.DELETE, tableName, key, new byte[0]);
        
        pendingOperations.add(new PendingOperation(OperationType.DELETE, tableName, key, null));
        
        logger.trace("Transaction {} registered DELETE: table={}, key={}", transactionId, tableName, key);
    }
    
    /**
     * Confirma la transacción, aplicando todos los cambios
     */
    public synchronized void commit() throws IOException {
        checkActive();
        
        try {
            // Escribir COMMIT en WAL
            wal.writeCommit(transactionId);
            wal.close(); // Cerrar WAL pero no eliminarlo
            
            // Aplicar operaciones pendientes a la base de datos
            applyPendingOperations();
            
            // Marcar como confirmada
            committed = true;
            active = false;
            
            logger.info("Transaction {} committed", transactionId);
            
        } catch (Exception e) {
            logger.error("Error committing transaction {}: {}", transactionId, e.getMessage());
            rollback();
            throw e;
        }
    }
    
    /**
     * Revierte la transacción, descartando todos los cambios
     */
    public synchronized void rollback() throws IOException {
        if (!active) {
            return; // Ya fue confirmada o revertida
        }
        
        try {
            // Escribir ROLLBACK en WAL
            wal.writeRollback(transactionId);
            
            // Descartar operaciones pendientes
            pendingOperations.clear();
            
            rolledBack = true;
            active = false;
            
            logger.info("Transaction {} rolled back", transactionId);
            
        } finally {
            closeWal();
        }
    }
    
    /**
     * Cierra la transacción (hace rollback si no fue confirmada)
     */
    @Override
    public void close() throws IOException {
        if (active) {
            logger.warn("Transaction {} closed without commit, rolling back", transactionId);
            rollback();
        } else {
            closeWal();
        }
    }
    
    private void checkActive() {
        if (!isActive()) {
            throw new IllegalStateException(
                "Transacción no activa: " + 
                (committed ? "ya confirmada" : rolledBack ? "ya revertida" : "cerrada")
            );
        }
    }
    
    @SuppressWarnings("unchecked")
    private void applyPendingOperations() {
        Map<String, Object> dbData = db.getData();
        
        for (PendingOperation op : pendingOperations) {
            switch (op.type) {
                case INSERT:
                    dbData.computeIfAbsent(op.tableName, k -> new java.util.HashMap<>());
                    Map<String, Object> insertTable = (Map<String, Object>) dbData.get(op.tableName);
                    insertTable.put(op.key, op.data);
                    break;
                    
                case UPDATE:
                    Object tableObj = dbData.get(op.tableName);
                    if (tableObj instanceof Map) {
                        Map<String, Object> updateTable = (Map<String, Object>) tableObj;
                        if (updateTable.containsKey(op.key)) {
                            Map<String, Object> existingRow = (Map<String, Object>) updateTable.get(op.key);
                            existingRow.putAll(op.data);
                        }
                    }
                    break;
                    
                case DELETE:
                    Object delTableObj = dbData.get(op.tableName);
                    if (delTableObj instanceof Map) {
                        Map<String, Object> delTable = (Map<String, Object>) delTableObj;
                        delTable.remove(op.key);
                    }
                    break;
            }
        }
    }
    
    private void closeWal() {
        try {
            if (!wal.isClosed()) {
                wal.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing WAL for transaction {}: {}", transactionId, e.getMessage());
        }
    }
    
    /**
     * Recupera la base de datos aplicando el WAL si existe
     */
    public static void recover(DeskDB db, Path walPath) throws IOException {
        if (!Files.exists(walPath)) {
            return;
        }
        
        List<Wal.WalEntry> entries = Wal.recover(walPath);
        if (entries.isEmpty()) {
            logger.info("No hay entradas WAL pendientes de aplicar");
            return;
        }
        
        Map<String, Object> dbData = db.getData();
        
        for (Wal.WalEntry entry : entries) {
            try {
                switch (entry.operation) {
                    case INSERT:
                        dbData.computeIfAbsent(entry.tableName, k -> new java.util.HashMap<>());
                        Map<String, Object> insertTable = (Map<String, Object>) dbData.get(entry.tableName);
                        if (entry.data != null && entry.data.length > 0) {
                            Map<String, Object> rowData = deserializeMap(entry.data);
                            insertTable.put(entry.key, rowData);
                        }
                        break;
                        
                    case UPDATE:
                        Object tableObj = dbData.get(entry.tableName);
                        if (tableObj instanceof Map && entry.data != null && entry.data.length > 0) {
                            Map<String, Object> updateTable = (Map<String, Object>) tableObj;
                            if (updateTable.containsKey(entry.key)) {
                                Map<String, Object> existingRow = (Map<String, Object>) updateTable.get(entry.key);
                                Map<String, Object> updateData = deserializeMap(entry.data);
                                existingRow.putAll(updateData);
                            }
                        }
                        break;
                        
                    case DELETE:
                        Object delTableObj = dbData.get(entry.tableName);
                        if (delTableObj instanceof Map) {
                            Map<String, Object> delTable = (Map<String, Object>) delTableObj;
                            delTable.remove(entry.key);
                        }
                        break;
                        
                    default:
                        logger.debug("Operación WAL ignorada en recuperación: {}", entry.operation);
                }
                
                logger.trace("Recovered operation: {} table={} key={}", entry.operation, entry.tableName, entry.key);
                
            } catch (Exception e) {
                logger.error("Error recovering operation: {}", entry, e);
            }
        }
        
        logger.info("Recovery complete: {} operations applied", entries.size());
        
        // Truncar WAL después de recuperación exitosa
        try (Wal wal = Wal.open(walPath)) {
            wal.truncate();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deserializeMap(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return new java.util.HashMap<>();
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        Map<String, Object> result = (Map<String, Object>) ois.readObject();
        ois.close();
        return result;
    }
    
    private static byte[] serializeMap(Map<String, Object> data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(data);
        oos.close();
        return baos.toByteArray();
    }
}
