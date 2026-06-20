package com.deskdb.storage;

import com.deskdb.core.Column;
import com.deskdb.core.DataType;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Define el layout de una fila en almacenamiento columnar.
 * 
 * Estructura:
 * - Header: rowId (8 bytes), version (4 bytes), timestamp (8 bytes)
 * - Column offsets: array de offsets relativos al inicio de datos
 * - Datos: valores de cada columna en orden
 * 
 * Optimizado para acceso rápido y versión MVCC.
 */
public class RowLayout {
    
    public static final int HEADER_SIZE = 20; // rowId(8) + version(4) + timestamp(8)
    public static final int MAX_COLUMNS = 256;
    
    private final List<Column> schema;
    private final Map<String, Integer> columnIndex;
    
    public RowLayout(List<Column> schema) {
        this.schema = new ArrayList<>(schema);
        this.columnIndex = new HashMap<>();
        for (int i = 0; i < schema.size(); i++) {
            columnIndex.put(schema.get(i).getName(), i);
        }
    }
    
    /**
     * Calcula el tamaño máximo necesario para una fila.
     * Para tipos variables, usa un tamaño estimado razonable (256 bytes).
     */
    public int calculateMaxRowSize() {
        int size = HEADER_SIZE;
        size += schema.size() * 4; // offsets (4 bytes c/u)
        
        for (Column col : schema) {
            if (col.getType().isVariableLength()) {
                size += 256; // Tamaño estimado para tipos variables
            } else {
                size += col.getType().getMaxSize();
            }
        }
        
        return size;
    }
    
    /**
     * Serializa una fila en un ByteBuffer.
     * 
     * @param buffer Buffer donde escribir
     * @param rowId ID de la fila
     * @param version Versión MVCC
     * @param timestamp Timestamp de la versión
     * @param values Valores de las columnas en orden del schema
     * @return Bytes escritos
     */
    public int serialize(ByteBuffer buffer, long rowId, int version, long timestamp, Object[] values) {
        if (values.length != schema.size()) {
            throw new IllegalArgumentException(
                "Expected " + schema.size() + " values, got " + values.length);
        }
        
        int startPos = buffer.position();
        
        // Header
        buffer.putLong(rowId);
        buffer.putInt(version);
        buffer.putLong(timestamp);
        
        // Placeholder para offsets (se llenará después)
        int offsetsPos = buffer.position();
        buffer.position(offsetsPos + schema.size() * 4);
        
        // Escribir datos y guardar offsets
        int dataStart = buffer.position();
        for (int i = 0; i < schema.size(); i++) {
            // Guardar offset relativo
            int offset = buffer.position() - dataStart;
            buffer.putInt(offsetsPos + i * 4, offset);
            
            // Escribir valor
            writeValue(buffer, schema.get(i), values[i]);
        }
        
        return buffer.position() - startPos;
    }
    
    /**
     * Deserializa una fila desde un ByteBuffer.
     * 
     * @param buffer Buffer con los datos
     * @param rowId ID esperado (para validación)
     * @return Array con los valores deserializados en orden del schema
     */
    public Object[] deserialize(ByteBuffer buffer, long rowId) {
        int startPos = buffer.position();
        
        // Leer header
        long readRowId = buffer.getLong();
        if (readRowId != rowId) {
            throw new IllegalStateException("Row ID mismatch: expected " + rowId + ", got " + readRowId);
        }
        
        int version = buffer.getInt();
        long timestamp = buffer.getLong();
        
        // Leer offsets
        int dataStart = startPos + HEADER_SIZE + schema.size() * 4;
        int[] offsets = new int[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            offsets[i] = buffer.getInt();
        }
        
        // Leer valores
        Object[] values = new Object[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            int offset = dataStart + offsets[i];
            buffer.position(offset);
            values[i] = readValue(buffer, schema.get(i));
        }
        
        // Restaurar posición original
        buffer.position(startPos);
        
        return values;
    }
    
    /**
     * Lee un valor específico de una columna sin deserializar toda la fila.
     * 
     * @param buffer Buffer con los datos de la fila
     * @param columnName Nombre de la columna
     * @return Valor de la columna
     */
    public Object readColumn(ByteBuffer buffer, long rowId, String columnName) {
        Integer colIdx = columnIndex.get(columnName);
        if (colIdx == null) {
            throw new IllegalArgumentException("Column not found: " + columnName);
        }
        
        int startPos = buffer.position();
        
        // Saltar header
        buffer.position(startPos + 8 + 4 + 8); // rowId + version + timestamp
        
        // Leer offset de la columna
        int offsetPos = startPos + HEADER_SIZE + colIdx * 4;
        int offset = buffer.getInt(offsetPos);
        
        // Leer valor
        int dataStart = startPos + HEADER_SIZE + schema.size() * 4;
        buffer.position(dataStart + offset);
        Object value = readValue(buffer, schema.get(colIdx));
        
        // Restaurar posición
        buffer.position(startPos);
        
        return value;
    }
    
    /**
     * Escribe un valor según su tipo.
     */
    private void writeValue(ByteBuffer buffer, Column column, Object value) {
        if (value == null) {
            buffer.put((byte) 0); // null flag
            return;
        }
        
        buffer.put((byte) 1); // not null
        
        switch (column.getType()) {
            case BOOLEAN:
                buffer.put((Boolean) value ? (byte) 1 : (byte) 0);
                break;
            case INT:
                buffer.putInt((Integer) value);
                break;
            case LONG:
            case DATE:
            case TIMESTAMP:
                buffer.putLong((Long) value);
                break;
            case DOUBLE:
                buffer.putDouble((Double) value);
                break;
            case STRING:
            case JSON:
                byte[] bytes = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                PrimitiveSerializer.writeVarInt(buffer, bytes.length);
                buffer.put(bytes);
                break;
            case BLOB:
                byte[] blob = (byte[]) value;
                PrimitiveSerializer.writeVarInt(buffer, blob.length);
                buffer.put(blob);
                break;
            default:
                throw new IllegalArgumentException("Unsupported type: " + column.getType());
        }
    }
    
    /**
     * Lee un valor según su tipo.
     */
    private Object readValue(ByteBuffer buffer, Column column) {
        byte isNull = buffer.get();
        if (isNull == 0) {
            return null;
        }
        
        switch (column.getType()) {
            case BOOLEAN:
                return buffer.get() != 0;
            case INT:
                return buffer.getInt();
            case LONG:
            case DATE:
            case TIMESTAMP:
                return buffer.getLong();
            case DOUBLE:
                return buffer.getDouble();
            case STRING:
            case JSON:
                int strLen = PrimitiveSerializer.readVarInt(buffer);
                byte[] strBytes = new byte[strLen];
                buffer.get(strBytes);
                return new String(strBytes, java.nio.charset.StandardCharsets.UTF_8);
            case BLOB:
                int blobLen = PrimitiveSerializer.readVarInt(buffer);
                byte[] blob = new byte[blobLen];
                buffer.get(blob);
                return blob;
            default:
                throw new IllegalArgumentException("Unsupported type: " + column.getType());
        }
    }
    
    /**
     * Obtiene el índice de una columna por nombre.
     */
    public int getColumnIndex(String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null) {
            throw new IllegalArgumentException("Column not found: " + columnName);
        }
        return idx;
    }
    
    /**
     * Obtiene el schema de esta fila.
     */
    public List<Column> getSchema() {
        return Collections.unmodifiableList(schema);
    }
    
    /**
     * Obtiene el número de columnas.
     */
    public int getColumnCount() {
        return schema.size();
    }
}
