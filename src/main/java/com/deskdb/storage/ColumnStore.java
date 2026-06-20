package com.deskdb.storage;

import com.deskdb.core.DataType;
import com.deskdb.core.Column;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Almacenamiento columnar que organiza los datos por columnas en lugar de filas.
 * Permite lecturas parciales eficientes y compresión por columna.
 */
public class ColumnStore {
    private final String tableName;
    private final List<String> columnNames;
    private final Map<String, DataType> columnTypes;
    private final PageManager pageManager;
    
    // Por columna: lista de bloques de datos (cada bloque es una página o fragmento)
    private final Map<String, List<ColumnBlock>> columnData;
    
    // Mapeo rowId -> posición en cada columna
    private final Map<Long, Map<String, Integer>> rowPositions;
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private int rowCount = 0;
    
    public ColumnStore(String tableName, List<Column> schema, PageManager pageManager) {
        this.tableName = tableName;
        this.pageManager = pageManager;
        this.columnNames = new ArrayList<>();
        this.columnTypes = new LinkedHashMap<>();
        this.columnData = new HashMap<>();
        this.rowPositions = new HashMap<>();
        
        for (Column col : schema) {
            columnNames.add(col.getName());
            columnTypes.put(col.getName(), col.getType());
            columnData.put(col.getName(), new ArrayList<>());
        }
    }
    
    /**
     * Inserta una fila en el almacenamiento columnar.
     */
    public long insert(Map<String, Object> values) {
        lock.writeLock().lock();
        try {
            long rowId = rowCount++;
            Map<String, Integer> positions = new HashMap<>();
            
            for (String colName : columnNames) {
                Object value = values.getOrDefault(colName, null);
                List<ColumnBlock> blocks = columnData.get(colName);
                
                // Obtener o crear el último bloque
                ColumnBlock lastBlock = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
                if (lastBlock == null || lastBlock.isFull()) {
                    lastBlock = new ColumnBlock(columnTypes.get(colName), pageManager);
                    blocks.add(lastBlock);
                }
                
                int position = lastBlock.append(value);
                positions.put(colName, position);
            }
            
            rowPositions.put(rowId, positions);
            return rowId;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Lee un valor específico de una columna para una fila.
     */
    public Object getValue(long rowId, String columnName) {
        lock.readLock().lock();
        try {
            Map<String, Integer> positions = rowPositions.get(rowId);
            if (positions == null) {
                return null;
            }
            
            Integer position = positions.get(columnName);
            if (position == null) {
                return null;
            }
            
            List<ColumnBlock> blocks = columnData.get(columnName);
            // Encontrar el bloque que contiene esta posición
            int cumulative = 0;
            for (ColumnBlock block : blocks) {
                if (position < cumulative + block.size()) {
                    return block.get(position - cumulative);
                }
                cumulative += block.size();
            }
            
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Lee todos los valores de una columna para un conjunto de rowIds.
     * Optimizado para lecturas parciales.
     */
    public List<Object> getColumnValues(String columnName, List<Long> rowIds) {
        lock.readLock().lock();
        try {
            List<Object> results = new ArrayList<>(rowIds.size());
            List<ColumnBlock> blocks = columnData.get(columnName);
            
            if (blocks.isEmpty()) {
                return results;
            }
            
            for (Long rowId : rowIds) {
                Map<String, Integer> positions = rowPositions.get(rowId);
                if (positions == null) {
                    results.add(null);
                    continue;
                }
                
                Integer position = positions.get(columnName);
                if (position == null) {
                    results.add(null);
                    continue;
                }
                
                // Encontrar el bloque y obtener el valor
                int cumulative = 0;
                Object value = null;
                for (ColumnBlock block : blocks) {
                    if (position < cumulative + block.size()) {
                        value = block.get(position - cumulative);
                        break;
                    }
                    cumulative += block.size();
                }
                results.add(value);
            }
            
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Actualiza un valor en una columna específica.
     */
    public void updateValue(long rowId, String columnName, Object newValue) {
        lock.writeLock().lock();
        try {
            Map<String, Integer> positions = rowPositions.get(rowId);
            if (positions == null) {
                throw new IllegalArgumentException("Row not found: " + rowId);
            }
            
            Integer position = positions.get(columnName);
            if (position == null) {
                throw new IllegalArgumentException("Column not found: " + columnName);
            }
            
            List<ColumnBlock> blocks = columnData.get(columnName);
            int cumulative = 0;
            for (ColumnBlock block : blocks) {
                if (position < cumulative + block.size()) {
                    block.set(position - cumulative, newValue);
                    return;
                }
                cumulative += block.size();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Elimina una fila (marca como eliminada, no compacta inmediatamente).
     */
    public void delete(long rowId) {
        lock.writeLock().lock();
        try {
            rowPositions.remove(rowId);
            rowCount--;
            // Nota: En una implementación completa, marcaríamos las celdas como eliminadas
            // y haríamos compactación periódica.
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Escanea una columna aplicando un filtro.
     * Retorna los rowIds que cumplen el predicado.
     */
    public List<Long> scanColumn(String columnName, Predicate<Object> predicate) {
        lock.readLock().lock();
        try {
            List<Long> matchingRowIds = new ArrayList<>();
            List<ColumnBlock> blocks = columnData.get(columnName);
            
            if (blocks.isEmpty()) {
                return matchingRowIds;
            }
            
            // Invertir mapeo: posición -> rowId
            Map<Integer, Long> positionToRowId = new HashMap<>();
            for (Map.Entry<Long, Map<String, Integer>> entry : rowPositions.entrySet()) {
                Integer pos = entry.getValue().get(columnName);
                if (pos != null) {
                    positionToRowId.put(pos, entry.getKey());
                }
            }
            
            int cumulative = 0;
            for (ColumnBlock block : blocks) {
                for (int i = 0; i < block.size(); i++) {
                    Object value = block.get(i);
                    if (predicate.test(value)) {
                        Long rowId = positionToRowId.get(cumulative + i);
                        if (rowId != null) {
                            matchingRowIds.add(rowId);
                        }
                    }
                }
                cumulative += block.size();
            }
            
            return matchingRowIds;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public int getRowCount() {
        lock.readLock().lock();
        try {
            return rowCount;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public List<String> getColumnNames() {
        return new ArrayList<>(columnNames);
    }
    
    /**
     * Bloque de datos para una columna.
     * Almacena valores del mismo tipo en un ByteBuffer contiguo.
     */
    private static class ColumnBlock {
        private final DataType dataType;
        private final PageManager pageManager;
        private final Page page;
        private int size = 0;
        private static final int MAX_ENTRIES_PER_BLOCK = 1000;
        
        public ColumnBlock(DataType dataType, PageManager pageManager) {
            this.dataType = dataType;
            this.pageManager = pageManager;
            try {
                this.page = pageManager.allocatePage(Page.TYPE_DATA);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to allocate page", e);
            }
        }
        
        public boolean isFull() {
            int entrySize = getEntrySize(dataType);
            int maxEntriesPerPage = (Page.PAGE_SIZE - Page.PAGE_HEADER_SIZE) / entrySize;
            return size >= Math.min(MAX_ENTRIES_PER_BLOCK, maxEntriesPerPage);
        }
        
        public int size() {
            return size;
        }
        
        public int append(Object value) {
            int position = size;
            ByteBuffer buffer = page.getByteBuffer();
            int offset = calculateOffset(position);
            
            synchronized (this) {
                buffer.position(offset);
                PrimitiveSerializer.write(buffer, value, dataType);
                size++;
            }
            
            return position;
        }
        
        public Object get(int position) {
            ByteBuffer buffer = page.getByteBuffer();
            int offset = calculateOffset(position);
            
            synchronized (this) {
                buffer.position(offset);
                return PrimitiveSerializer.read(buffer, dataType);
            }
        }
        
        public void set(int position, Object newValue) {
            ByteBuffer buffer = page.getByteBuffer();
            int offset = calculateOffset(position);
            
            synchronized (this) {
                buffer.position(offset);
                PrimitiveSerializer.write(buffer, newValue, dataType);
            }
        }
        
        private int calculateOffset(int position) {
            // Cada entrada tiene tamaño variable según el tipo
            // Para simplificar, asumimos un tamaño máximo fijo por tipo
            int entrySize = getEntrySize(dataType);
            int maxEntriesPerPage = (Page.PAGE_SIZE - Page.PAGE_HEADER_SIZE) / entrySize;
            
            // Si la posición excede la capacidad de una página, necesitamos múltiples páginas
            // Por simplicidad, lanzamos excepción si se excede (en producción usaríamos linked pages)
            if (position >= maxEntriesPerPage) {
                throw new IllegalStateException("Block full: position " + position + " exceeds max " + maxEntriesPerPage);
            }
            
            return Page.PAGE_HEADER_SIZE + (position * entrySize);
        }
        
        private int getEntrySize(DataType type) {
            switch (type) {
                case BOOLEAN: return 1;
                case INT: return 4;
                case LONG: return 8;
                case DOUBLE: return 8;
                case STRING: return 256; // Tamaño máximo estimado
                case DATE: return 8;
                case TIMESTAMP: return 12;
                case BLOB: return 1024;
                default: return 64;
            }
        }
    }
    
    @FunctionalInterface
    public interface Predicate<T> {
        boolean test(T value);
    }
}
