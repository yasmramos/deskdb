package com.deskdb.storage;

import com.deskdb.core.Row;
import com.deskdb.core.Column;
import com.deskdb.core.DataType;
import com.deskdb.transaction.MVCC;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archivo de datos para almacenar filas de una tabla usando almacenamiento columnar.
 * Integra ColumnStore, RowLayout y MVCC para concurrencia y eficiencia.
 */
public class DataFile {
    private final String filePath;
    private final ColumnStore columnStore;
    private final RowLayout rowLayout;
    private final MVCC mvcc;
    private final List<Column> columns;
    private long nextRowId = 1;
    private long currentTransactionVersion = 0;

    public DataFile(String filePath, List<Column> columns) throws IOException {
        this.filePath = filePath;
        this.columns = columns;
        
        // Inicializar componentes de la arquitectura columnar
        PageManager pageManager = new PageManager(java.nio.file.Paths.get(filePath + ".db"));
        this.columnStore = new ColumnStore("table", columns, pageManager);
        this.rowLayout = new RowLayout(columns);
        this.mvcc = new MVCC();
        
        loadMetadata();
    }

    private void loadMetadata() throws IOException {
        File metaFile = new File(filePath + ".meta");
        if (metaFile.exists()) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(metaFile))) {
                nextRowId = in.readLong();
                currentTransactionVersion = in.readLong();
            }
        } else {
            saveMetadata();
        }
    }

    private void saveMetadata() throws IOException {
        File metaFile = new File(filePath + ".meta");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(metaFile))) {
            out.writeLong(nextRowId);
            out.writeLong(currentTransactionVersion);
        }
    }

    public synchronized long write(Row row) throws IOException {
        long rowId = row.getRowId();
        if (rowId == -1) {
            rowId = nextRowId++;
            row = new Row(rowId, row.getValues());
        }

        // Iniciar transacción MVCC
        long transactionVersion = mvcc.beginTransaction();
        
        try {
            // Insertar/actualizar en ColumnStore
            Map<String, Object> values = row.getValues();
            if (columnStore.getRowCount() <= rowId) {
                // Nueva fila
                columnStore.insert(values);
            } else {
                // Actualizar fila existente
                for (Column col : columns) {
                    Object value = values.get(col.getName());
                    columnStore.updateValue(rowId, col.getName(), value);
                }
            }
            
            // Registrar en MVCC
            mvcc.write(rowId, values, transactionVersion);
            currentTransactionVersion = transactionVersion;
        } catch (Exception e) {
            throw e;
        }
        
        return rowId;
    }

    public synchronized Row read(long rowId) throws IOException {
        // Obtener snapshot actual
        long snapshotVersion = mvcc.beginTransaction();
        
        try {
            // Leer desde MVCC primero (para consistencia transaccional)
            Map<String, Object> mvccData = mvcc.read(rowId, snapshotVersion);
            
            if (mvccData != null) {
                return new Row(rowId, mvccData);
            }
            
            // Fallback: leer directamente de ColumnStore si no hay versión MVCC
            Map<String, Object> values = new HashMap<>();
            for (Column col : columns) {
                Object value = columnStore.getValue(rowId, col.getName());
                values.put(col.getName(), value);
            }
            
            return new Row(rowId, values);
        } finally {
            // No necesitamos hacer nada aquí ya que MVCC usa snapshot isolation
        }
    }

    public synchronized List<Row> readRows(List<Long> rowIds) throws IOException {
        List<Row> result = new ArrayList<>();
        for (Long rowId : rowIds) {
            Row row = read(rowId);
            if (row != null) {
                result.add(row);
            }
        }
        return result;
    }

    public synchronized List<Row> readAll() throws IOException {
        List<Row> result = new ArrayList<>();
        int rowCount = columnStore.getRowCount();
        for (long rowId = 0; rowId < rowCount; rowId++) {
            Row row = read(rowId);
            if (row != null && !isDeleted(rowId)) {
                result.add(row);
            }
        }
        return result;
    }

    public synchronized Object readColumn(long rowId, String columnName) throws IOException {
        Row row = read(rowId);
        if (row == null) {
            return null;
        }
        return row.get(columnName);
    }

    public synchronized void delete(long rowId) throws IOException {
        long transactionVersion = mvcc.beginTransaction();
        mvcc.delete(rowId, transactionVersion);
        columnStore.delete(rowId);
        currentTransactionVersion = transactionVersion;
    }

    private boolean isDeleted(long rowId) {
        long snapshotVersion = mvcc.beginTransaction();
        Map<String, Object> data = mvcc.read(rowId, snapshotVersion);
        return data == null;
    }

    public synchronized long count() {
        int totalCount = columnStore.getRowCount();
        long deletedCount = 0;
        // Contar filas eliminadas
        for (long rowId = 0; rowId < totalCount; rowId++) {
            if (isDeleted(rowId)) {
                deletedCount++;
            }
        }
        return totalCount - deletedCount;
    }

    public String getFilePath() {
        return filePath;
    }

    public synchronized void close() throws IOException {
        saveMetadata();
        // ColumnStore no tiene método close en la implementación actual
    }
}
