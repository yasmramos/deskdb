package com.deskdb.storage;

import com.deskdb.core.Row;
import com.deskdb.util.Serializer;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archivo de datos para almacenar filas de una tabla.
 */
public class DataFile {
    private final String filePath;
    private RandomAccessFile file;
    private final Map<Long, Long> rowIdToOffset;  // rowId -> posición en archivo
    private final Map<Long, Row> cache;            // Cache LRU simple
    private static final int CACHE_SIZE = 100;

    public DataFile(String filePath) throws IOException {
        this.filePath = filePath;
        this.rowIdToOffset = new ConcurrentHashMap<>();
        this.cache = new LinkedHashMap<Long, Row>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Row> eldest) {
                return size() > CACHE_SIZE;
            }
        };
        
        File f = new File(filePath);
        if (!f.exists()) {
            f.createNewFile();
        }
        
        this.file = new RandomAccessFile(f, "rw");
        loadIndex();
    }

    private void loadIndex() throws IOException {
        file.seek(0);
        long offset = 0;
        
        while (offset < file.length()) {
            file.seek(offset);
            try {
                int size = file.readInt();
                if (size <= 0 || size > 10_000_000) break; // Corrupción o EOF
                
                byte[] data = new byte[size];
                int read = file.read(data);
                if (read != size) break;
                
                Row row = Serializer.deserializeRow(data);
                if (row != null) {
                    rowIdToOffset.put(row.getRowId(), offset);
                    cache.put(row.getRowId(), row);
                }
                
                offset += 4 + size;
            } catch (Exception e) {
                break; // Fin del archivo o corrupción
            }
        }
    }

    public synchronized void write(Row row) throws IOException {
        Long existingOffset = rowIdToOffset.get(row.getRowId());
        
        byte[] data = Serializer.serializeRow(row);
        
        if (existingOffset != null) {
            // Actualizar existente
            file.seek(existingOffset);
            file.writeInt(data.length);
            file.write(data);
        } else {
            // Nueva fila
            long offset = file.length();
            file.seek(offset);
            file.writeInt(data.length);
            file.write(data);
            rowIdToOffset.put(row.getRowId(), offset);
        }
        
        cache.put(row.getRowId(), row);
    }

    public synchronized Row read(long rowId) throws IOException {
        if (cache.containsKey(rowId)) {
            return cache.get(rowId);
        }
        
        Long offset = rowIdToOffset.get(rowId);
        if (offset == null) return null;
        
        file.seek(offset);
        int size = file.readInt();
        byte[] data = new byte[size];
        file.read(data);
        
        Row row = Serializer.deserializeRow(data);
        cache.put(rowId, row);
        return row;
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
        for (Long rowId : new ArrayList<>(rowIdToOffset.keySet())) {
            Row row = read(rowId);
            if (row != null) {
                result.add(row);
            }
        }
        return result;
    }

    public synchronized void delete(long rowId) throws IOException {
        rowIdToOffset.remove(rowId);
        cache.remove(rowId);
        // Nota: No eliminamos físicamente del archivo, se podría implementar compactación
    }

    public synchronized long count() {
        return rowIdToOffset.size();
    }

    public String getFilePath() {
        return filePath;
    }

    public synchronized void close() throws IOException {
        if (file != null) {
            file.close();
        }
    }
}
