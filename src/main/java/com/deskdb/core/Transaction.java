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

public class Transaction implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);
    
    private final DeskDB db;
    private boolean active = true;
    private final Map<String, Map<Long, Row>> snapshots = new HashMap<>();
    private final Map<String, Map<Long, Row>> pendingChanges = new HashMap<>();
    private final Map<String, Long> nextRowIds = new HashMap<>();
    private boolean committed = false;

    public Transaction(DeskDB db) { 
        this.db = db;
        // Capturar snapshot de todas las tablas para rollback y aislamiento
        for (Map.Entry<String, Table> entry : db.getTables().entrySet()) {
            try {
                Map<Long, Row> snapshot = new HashMap<>(entry.getValue().getData());
                snapshots.put(entry.getKey(), snapshot);
                pendingChanges.put(entry.getKey(), new HashMap<>());
                // Calcular el siguiente ID disponible para cada tabla
                long maxId = snapshot.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
                nextRowIds.put(entry.getKey(), maxId + 1);
            } catch (Exception e) {
                logger.warn("Error al capturar snapshot: {}", e.getMessage());
            }
        }
    }

    public TableOperations table(String tableName) { 
        return db.table(tableName, this); 
    }

    public void commit() {
        if (!active) throw new IllegalStateException("Transaction already closed");
        active = false;
        committed = true;
        
        // Aplicar cambios pendientes a las tablas reales
        for (Map.Entry<String, Map<Long, Row>> entry : pendingChanges.entrySet()) {
            String tableName = entry.getKey();
            Table table = db.getTable(tableName);
            if (table != null) {
                Map<Long, Row> tableData = table.getData();
                
                // Primero, calcular el siguiente ID disponible
                long nextId = tableData.keySet().stream().mapToLong(Long::longValue).max().orElse(0) + 1;
                
                // Procesar todos los cambios
                for (Map.Entry<Long, Row> changeEntry : entry.getValue().entrySet()) {
                    if (changeEntry.getValue() == null) {
                        // Eliminación
                        tableData.remove(changeEntry.getKey());
                    } else {
                        Row row = changeEntry.getValue();
                        if (row.getRowId() == 0) {
                            // Es una inserción nueva, asignar ID real único
                            Row newRow = new Row(nextId++, row.getValues());
                            tableData.put(nextId - 1, newRow);
                        } else {
                            // Actualización o inserción con ID específico
                            tableData.put(changeEntry.getKey(), changeEntry.getValue());
                        }
                    }
                }
            }
        }
    }

    public void rollback() {
        if (!active || committed) return;
        active = false;
        
        // NO restaurar snapshots - los commits son atómicos y persistentes
        // El rollback solo descarta cambios pendientes de esta transacción
        logger.debug("Transaction rolled back");
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
     * Aplica un cambio pendiente en esta transacción.
     */
    public void applyChange(String tableName, long rowId, Row row) {
        Map<Long, Row> changes = pendingChanges.computeIfAbsent(tableName, k -> new HashMap<>());
        if (row == null) {
            changes.remove(rowId);
        } else {
            // Si rowId es 0, es una nueva inserción - asignar ID único
            if (rowId == 0) {
                long nextId = nextRowIds.computeIfAbsent(tableName, k -> 1L);
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
     * This is a simplified implementation that only deletes the WAL if it exists.
     */
    public static void recover(DeskDB db, Path walPath) throws IOException {
        // Simplified implementation: in production, it would read the WAL and apply pending committed operations
        // For now, we just delete the WAL assuming it was processed.
        logger.info("Recovering from WAL: {}", walPath);
        if (Files.exists(walPath)) {
            // Real WAL replay logic would go here
            // For simplicity, we delete it to avoid errors on subsequent opens
            Files.delete(walPath);
            logger.info("WAL deleted after simulated recovery");
        }
    }
    
    /**
     * Ejecuta un SELECT dentro de esta transacción, leyendo desde el snapshot + cambios pendientes.
     */
    public List<Row> select(String tableName, List<Filter> filters) throws Exception {
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
