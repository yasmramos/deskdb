package com.deskdb.storage;

import com.deskdb.core.Row;
import com.deskdb.core.Column;
import com.deskdb.core.DataType;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archivo de datos para almacenar filas de una tabla usando almacenamiento row-based simple.
 * Formato: [rowId(8)][size(4)][data...] para cada fila.
 */
public class DataFile {
    private final String filePath;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final Map<Long, Long> rowIdToOffset;  // rowId -> offset en archivo
    private final Map<Long, Row> cache;            // Cache LRU simple
    private final List<Column> columns;
    private long nextRowId = 1;
    private static final int CACHE_SIZE = 1000;

    public DataFile(String filePath, List<Column> columns) throws IOException {
        this.filePath = filePath;
        this.columns = columns;
        this.rowIdToOffset = new ConcurrentHashMap<>();
        this.cache = new LinkedHashMap<Long, Row>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Row> eldest) {
                return size() > CACHE_SIZE;
            }
        };

        File file = new File(filePath);
        this.raf = new RandomAccessFile(file, "rw");
        this.channel = raf.getChannel();
        
        loadIndex();
    }

    private void loadIndex() throws IOException {
        File indexFile = new File(filePath + ".idx");
        if (indexFile.exists()) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(indexFile))) {
                nextRowId = in.readLong();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    long rowId = in.readLong();
                    long offset = in.readLong();
                    rowIdToOffset.put(rowId, offset);
                }
            }
        } else {
            // Archivo nuevo, escribir header
            saveIndex();
        }
    }

    private void saveIndex() throws IOException {
        File indexFile = new File(filePath + ".idx");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(indexFile))) {
            out.writeLong(nextRowId);
            out.writeInt(rowIdToOffset.size());
            for (Map.Entry<Long, Long> entry : rowIdToOffset.entrySet()) {
                out.writeLong(entry.getKey());
                out.writeLong(entry.getValue());
            }
        }
    }

    public synchronized long write(Row row) throws IOException {
        long rowId = row.getRowId();
        if (rowId == -1) {
            rowId = nextRowId++;
            row = new Row(rowId, row.getValues());
        }

        // Serializar fila
        byte[] data = serializeRow(row);
        ByteBuffer buffer = ByteBuffer.allocate(12 + data.length); // rowId(8) + size(4) + data
        buffer.putLong(rowId);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        // Escribir al final del archivo
        long offset = raf.length();
        raf.seek(offset);
        channel.write(buffer);
        
        // Actualizar índice
        rowIdToOffset.put(rowId, offset);
        cache.put(rowId, row);
        
        return rowId;
    }

    public synchronized Row read(long rowId) throws IOException {
        // Verificar cache
        if (cache.containsKey(rowId)) {
            return cache.get(rowId);
        }

        // Buscar offset
        Long offset = rowIdToOffset.get(rowId);
        if (offset == null) {
            return null;
        }

        // Leer desde archivo
        raf.seek(offset);
        long storedRowId = raf.readLong();
        int size = raf.readInt();
        byte[] data = new byte[size];
        raf.readFully(data);

        Row row = deserializeRow(storedRowId, data);
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

    public synchronized Object readColumn(long rowId, String columnName) throws IOException {
        Row row = read(rowId);
        if (row == null) {
            return null;
        }
        return row.get(columnName);
    }

    public synchronized void delete(long rowId) throws IOException {
        Long offset = rowIdToOffset.remove(rowId);
        if (offset != null) {
            cache.remove(rowId);
            // Nota: No eliminamos físicamente del archivo, solo del índice
            // Se podría implementar compactación periódica
        }
    }

    public synchronized long count() {
        return rowIdToOffset.size();
    }

    public String getFilePath() {
        return filePath;
    }

    public synchronized void close() throws IOException {
        saveIndex();
        channel.close();
        raf.close();
    }

    private byte[] serializeRow(Row row) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        
        for (Column col : columns) {
            Object value = row.get(col.getName());
            writeValue(out, value, col.getType());
        }
        
        return baos.toByteArray();
    }

    private void writeValue(DataOutputStream out, Object value, DataType type) throws IOException {
        if (value == null) {
            out.writeBoolean(false);
            return;
        }
        
        out.writeBoolean(true);
        
        switch (type) {
            case STRING:
            case JSON:
                byte[] strBytes = ((String) value).getBytes("UTF-8");
                out.writeInt(strBytes.length);
                out.write(strBytes);
                break;
            case INT:
                out.writeInt((Integer) value);
                break;
            case LONG:
            case DATE:
            case TIMESTAMP:
                out.writeLong((Long) value);
                break;
            case DOUBLE:
                out.writeDouble((Double) value);
                break;
            case BOOLEAN:
                out.writeBoolean((Boolean) value);
                break;
            case BLOB:
                byte[] blobBytes = (byte[]) value;
                out.writeInt(blobBytes.length);
                out.write(blobBytes);
                break;
            default:
                // Fallback a serialización genérica
                byte[] bytes = value.toString().getBytes("UTF-8");
                out.writeInt(bytes.length);
                out.write(bytes);
        }
    }

    private Row deserializeRow(long rowId, byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        Map<String, Object> values = new HashMap<>();
        
        for (Column col : columns) {
            Object value = readValue(in, col.getType());
            values.put(col.getName(), value);
        }
        
        return new Row(rowId, values);
    }

    private Object readValue(DataInputStream in, DataType type) throws IOException {
        boolean isNull = !in.readBoolean();
        if (isNull) {
            return null;
        }
        
        switch (type) {
            case STRING:
            case JSON:
                int strLen = in.readInt();
                byte[] strBytes = new byte[strLen];
                in.readFully(strBytes);
                return new String(strBytes, "UTF-8");
            case INT:
                return in.readInt();
            case LONG:
            case DATE:
            case TIMESTAMP:
                return in.readLong();
            case DOUBLE:
                return in.readDouble();
            case BOOLEAN:
                return in.readBoolean();
            case BLOB:
                int blobLen = in.readInt();
                byte[] blobBytes = new byte[blobLen];
                in.readFully(blobBytes);
                return blobBytes;
            default:
                // Fallback
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                return new String(bytes, "UTF-8");
        }
    }
}
