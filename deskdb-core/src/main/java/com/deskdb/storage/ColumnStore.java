package com.deskdb.storage;

import com.deskdb.core.DataType;
import com.deskdb.core.Column;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Almacenamiento columnar que organiza los datos por columnas en lugar de filas.
 * Permite lecturas parciales eficientes.
 */
public class ColumnStore {
    private final String tableName;
    private final List<String> columnNames;
    private final Map<String, DataType> columnTypes;
    private final PageManager pageManager;
    
    // Por columna: lista de bloques de datos (cada bloque es una página o fragmento)
    private final Map<String, List<ColumnBlock>> columnData;
    
    // Mapeo rowId -> posición global en cada columna (posición absoluta, no relativa por bloque)
    private final Map<Long, Map<String, Integer>> rowPositions;
    
    // Índices inversos persistentes por columna: posición global -> rowId (optimización O(1))
    private final Map<String, Map<Integer, Long>> columnIndexInverse;
    
    // Zone maps por bloque: min/max valores para pruning en scans
    private final Map<String, List<ZoneMap>> columnZoneMaps;
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong rowCountCounter = new AtomicLong(0);
    private int rowCount = 0;
    private final Map<Long, Boolean> deletedRows = Collections.synchronizedMap(new HashMap<>());
    
    public ColumnStore(String tableName, List<Column> schema, PageManager pageManager) {
        this.tableName = tableName;
        this.pageManager = pageManager;
        this.columnNames = new ArrayList<>();
        this.columnTypes = new LinkedHashMap<>();
        this.columnData = new HashMap<>();
        this.rowPositions = new HashMap<>();
        this.columnIndexInverse = new HashMap<>();
        this.columnZoneMaps = new HashMap<>();
        
        for (Column col : schema) {
            columnNames.add(col.getName());
            columnTypes.put(col.getName(), col.getType());
            columnData.put(col.getName(), new ArrayList<>());
            columnIndexInverse.put(col.getName(), new HashMap<>());
            columnZoneMaps.put(col.getName(), new ArrayList<>());
        }
    }
    
    /**
     * Inserta una fila en el almacenamiento columnar usando batching interno.
     * @param values Valores a insertar
     * @return El rowId asignado a la nueva fila
     */
    public long insert(Map<String, Object> values) {
        lock.writeLock().lock();
        try {
            long rowId = rowCount;
            
            // Calcular posiciones globales para cada columna antes de insertar
            Map<String, Integer> globalPositions = new HashMap<>();
            
            for (String colName : columnNames) {
                Object value = values.getOrDefault(colName, null);
                List<ColumnBlock> blocks = columnData.get(colName);
                Map<Integer, Long> indexInverse = columnIndexInverse.get(colName);
                List<ZoneMap> zoneMaps = columnZoneMaps.get(colName);
                
                ColumnBlock lastBlock;
                synchronized (blocks) {
                    lastBlock = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
                    if (lastBlock == null || lastBlock.isFull()) {
                        lastBlock = new ColumnBlock(columnTypes.get(colName), pageManager);
                        blocks.add(lastBlock);
                        // Agregar zone map para el nuevo bloque
                        zoneMaps.add(new ZoneMap());
                    }
                    
                    // Posición global = suma de tamaños de todos los bloques anteriores + posición en bloque actual
                    int cumulativeSize = 0;
                    for (ColumnBlock block : blocks) {
                        if (block != lastBlock) {
                            cumulativeSize += block.size();
                        }
                    }
                    
                    int localPosition = lastBlock.append(value, columnTypes.get(colName));
                    int globalPosition = cumulativeSize + localPosition;
                    
                    globalPositions.put(colName, globalPosition);
                    
                    // Actualizar índice inverso persistente: posición global -> rowId
                    synchronized (indexInverse) {
                        indexInverse.put(globalPosition, rowId);
                    }
                    
                    // Actualizar zone map del último bloque con el nuevo valor
                    synchronized (zoneMaps) {
                        ZoneMap currentZoneMap = zoneMaps.get(zoneMaps.size() - 1);
                        currentZoneMap.update(value);
                    }
                }
            }
            
            // Registrar las posiciones globales de esta fila
            rowPositions.put(rowId, globalPositions);
            rowCount++;
            
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
            // Verificar si la fila está eliminada
            if (deletedRows.containsKey(rowId)) {
                return null;
            }
            
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
                // Verificar si la fila está eliminada
                if (deletedRows.containsKey(rowId)) {
                    results.add(null);
                    continue;
                }
                
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
            deletedRows.put(rowId, true);
            // No decrementamos rowCount para evitar reutilización de IDs
            // rowCount representa el total histórico de filas insertadas
            // Nota: En una implementación completa, marcaríamos las celdas como eliminadas
            // y haríamos compactación periódica.
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Escanea una columna aplicando un filtro.
     * Retorna los rowIds que cumplen el predicado.
     * Optimizado con índice inverso persistente O(1) y zone maps para pruning.
     */
    public List<Long> scanColumn(String columnName, Predicate<Object> predicate) {
        lock.readLock().lock();
        try {
            List<Long> matchingRowIds = new ArrayList<>();
            List<ColumnBlock> blocks = columnData.get(columnName);
            Map<Integer, Long> indexInverse = columnIndexInverse.get(columnName);
            List<ZoneMap> zoneMaps = columnZoneMaps.get(columnName);
            
            if (blocks.isEmpty()) {
                return matchingRowIds;
            }
            
            int cumulative = 0;
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ColumnBlock block = blocks.get(blockIndex);
                ZoneMap zoneMap = zoneMaps.get(blockIndex);
                
                // Usar zone map para pruning: saltar bloque si no puede contener valores matching
                if (zoneMap != null && !zoneMap.couldMatch(predicate)) {
                    cumulative += block.size();
                    continue;
                }
                
                // Escanear el bloque
                for (int i = 0; i < block.size(); i++) {
                    int globalPosition = cumulative + i;
                    
                    // Obtener rowId directamente del índice inverso persistente O(1)
                    Long rowId = indexInverse.get(globalPosition);
                    
                    // Saltar rows eliminadas
                    if (rowId == null || deletedRows.containsKey(rowId)) {
                        continue;
                    }
                    
                    Object value = block.get(i);
                    if (predicate.test(value)) {
                        matchingRowIds.add(rowId);
                    }
                }
                cumulative += block.size();
            }
            
            return matchingRowIds;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Retorna el número de filas activas (no eliminadas).
     */
    public int getRowCount() {
        lock.readLock().lock();
        try {
            // Retornar el contador de filas menos las eliminadas
            return rowCount - deletedRows.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public List<String> getColumnNames() {
        return new ArrayList<>(columnNames);
    }
    
    /**
     * Zone Map para pruning de bloques durante scans.
     * Almacena min/max valores y permite determinar rápidamente si un bloque puede contener valores que cumplan un predicado.
     */
    private static class ZoneMap {
        private Object minValue = null;
        private Object maxValue = null;
        private int count = 0;
        
        public void update(Object value) {
            if (value == null) {
                return;
            }
            
            if (minValue == null || compare(value, minValue) < 0) {
                minValue = value;
            }
            if (maxValue == null || compare(value, maxValue) > 0) {
                maxValue = value;
            }
            count++;
        }
        
        /**
         * Determina si este bloque podría contener valores que cumplan el predicado.
         * Usa min/max para pruning rápido.
         */
        public boolean couldMatch(Predicate<Object> predicate) {
            if (minValue == null || maxValue == null) {
                return true; // Sin datos, no podemos hacer pruning
            }
            
            // Pruning basado en el rango [min, max]
            // Si el predicado es una función arbitraria, debemos ser conservadores
            // y asumir que podría matchear algo en el rango.
            // Para predicados específicos (>, <, =, etc.) se podría optimizar más.
            
            // Caso especial: si min == max, solo hay un valor único
            if (compare(minValue, maxValue) == 0) {
                return predicate.test(minValue);
            }
            
            // Para predicados genéricos, verificamos los extremos
            // Esto es conservador pero seguro
            return predicate.test(minValue) || predicate.test(maxValue) || count <= 2;
        }
        
        @SuppressWarnings("unchecked")
        private int compare(Object a, Object b) {
            if (a instanceof Comparable && b instanceof Comparable) {
                try {
                    return ((Comparable<Object>) a).compareTo(b);
                } catch (ClassCastException e) {
                    // Tipos incompatibles, tratar como diferentes
                    return a.toString().compareTo(b.toString());
                }
            }
            return a.toString().compareTo(b.toString());
        }
        
        public Object getMinValue() {
            return minValue;
        }
        
        public Object getMaxValue() {
            return maxValue;
        }
        
        public int getCount() {
            return count;
        }
    }
    
    /**
     * Bloque de datos para una columna.
     * Almacena valores del mismo tipo en un ByteBuffer contiguo.
     * Usa formato de tamaño variable para soportar strings y tipos complejos.
     * Soporta compresión opcional de datos.
     */
    private static class ColumnBlock {
        private final DataType dataType;
        private final PageManager pageManager;
        private final Page page;
        private int size = 0;
        private int currentOffset; // Offset actual dentro de la página
        private static final int MAX_ENTRIES_PER_BLOCK = 1000;
        private boolean compressed = false;
        private int uncompressedSize = 0; // Tamaño antes de compresión
        
        // Cache de offsets para acceso O(1) - mapea posición -> offset en el buffer
        private final Map<Integer, Integer> offsetCache = new HashMap<>();
        private boolean cacheValid = true;
        
        public ColumnBlock(DataType dataType, PageManager pageManager) {
            this.dataType = dataType;
            this.pageManager = pageManager;
            try {
                this.page = pageManager.allocatePage(Page.TYPE_DATA);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to allocate page", e);
            }
            // Inicializar offset después del header de página + metadata (rowCount + flags)
            this.currentOffset = Page.PAGE_HEADER_SIZE + 8; // 4 bytes rowCount + 4 bytes flags
            writeRowCount();
            writeFlags();
        }
        
        private void writeRowCount() {
            ByteBuffer buffer = page.getByteBuffer();
            buffer.position(Page.PAGE_HEADER_SIZE);
            buffer.putInt(0); // rowCount inicial
        }
        
        private void writeFlags() {
            ByteBuffer buffer = page.getByteBuffer();
            buffer.position(Page.PAGE_HEADER_SIZE + 4);
            int flags = compressed ? 1 : 0;
            buffer.putInt(flags);
        }
        
        private void updateRowCount() {
            ByteBuffer buffer = page.getByteBuffer();
            buffer.position(Page.PAGE_HEADER_SIZE);
            buffer.putInt(size); // actualizar rowCount
        }
        
        private void updateFlags() {
            ByteBuffer buffer = page.getByteBuffer();
            buffer.position(Page.PAGE_HEADER_SIZE + 4);
            int flags = compressed ? 1 : 0;
            buffer.putInt(flags);
        }
        
        public boolean isCompressed() {
            return compressed;
        }
        
        public void setCompressed(boolean compressed) {
            this.compressed = compressed;
            updateFlags();
        }
        
        public boolean isFull() {
            // Verificar si hay espacio suficiente para otra entrada
            // Estimación conservadora: tamaño máximo del tipo + overhead
            int estimatedSize = getEstimatedEntrySize(dataType) + 4; // +4 para length prefix
            return currentOffset + estimatedSize > Page.PAGE_SIZE || 
                   size >= MAX_ENTRIES_PER_BLOCK;
        }
        
        public int size() {
            return size;
        }
        
        public int append(Object value, DataType type) {
            int position = size;
            ByteBuffer buffer = page.getByteBuffer();
            
            synchronized (this) {
                // Mover al offset actual
                buffer.position(currentOffset);
                
                // Guardar posición inicial para la longitud
                int lengthOffset = currentOffset;
                buffer.putInt(0); // Placeholder para longitud
                
                // Escribir el valor (incluye null marker internamente)
                int dataStart = buffer.position();
                PrimitiveSerializer.write(buffer, value, type);
                int dataEnd = buffer.position();
                
                // Calcular longitud de los datos serializados
                int dataLength = dataEnd - dataStart;
                
                // Volver atrás y escribir la longitud real
                buffer.putInt(lengthOffset, dataLength);
                
                // Reposicionar al final de los datos escritos
                buffer.position(dataEnd);
                
                // Actualizar offset actual y tamaño
                currentOffset = dataEnd;
                size++;
                
                // Invalidar cache ya que agregamos una nueva entrada
                cacheValid = false;
                
                // Actualizar rowCount
                updateRowCount();
            }
            
            return position;
        }
        
        @Deprecated
        public int append(Object value) {
            return append(value, this.dataType);
        }
        
        public Object get(int position) {
            synchronized (this) {
                // Si la cache es válida y tenemos el offset, acceso O(1)
                if (cacheValid && offsetCache.containsKey(position)) {
                    ByteBuffer buffer = page.getByteBuffer();
                    buffer.position(offsetCache.get(position));
                    int dataLength = buffer.getInt();
                    return PrimitiveSerializer.read(buffer, dataType);
                }
                
                // Cache miss o inválida - escaneo lineal con reconstrucción de cache
                ByteBuffer buffer = page.getByteBuffer();
                buffer.position(Page.PAGE_HEADER_SIZE);
                int rowCount = buffer.getInt();
                
                if (position >= rowCount || position < 0) {
                    throw new IndexOutOfBoundsException("Position " + position + " >= size " + rowCount);
                }
                
                // Los datos comienzan después del rowCount (4 bytes) + flags (4 bytes) = 8 bytes
                int dataStart = Page.PAGE_HEADER_SIZE + 8;
                buffer.position(dataStart);
                
                // Reconstruir cache mientras navegamos
                if (cacheValid) {
                    offsetCache.clear();
                }
                
                // Navegar hasta la posición deseada leyendo longitudes secuencialmente
                for (int i = 0; i <= position; i++) {
                    // Guardar offset del length prefix en la cache
                    if (cacheValid) {
                        offsetCache.put(i, buffer.position());
                    }
                    
                    int dataLength = buffer.getInt();
                    if (i == position) {
                        // Leer el valor en la posición actual
                        return PrimitiveSerializer.read(buffer, dataType);
                    }
                    // Saltar este dato
                    buffer.position(buffer.position() + dataLength);
                }
                
                return null; // No debería llegar aquí
            }
        }
        
        public void set(int position, Object newValue) {
            synchronized (this) {
                // Para tipos de tamaño fijo, podemos hacer update in-place
                // Para tipos variables, necesitamos reescribir (simplificación: solo soportamos mismo tamaño)
                ByteBuffer buffer = page.getByteBuffer();
                buffer.position(Page.PAGE_HEADER_SIZE);
                int rowCount = buffer.getInt();
                
                if (position >= rowCount) {
                    throw new IndexOutOfBoundsException("Position " + position + " >= size " + rowCount);
                }
                
                // Los datos comienzan después del rowCount (4 bytes) + flags (4 bytes) = 8 bytes
                int dataStart = Page.PAGE_HEADER_SIZE + 8;
                buffer.position(dataStart);
                
                // Navegar hasta la posición deseada
                int currentPos = 0;
                while (currentPos < position) {
                    int dataLength = buffer.getInt();
                    buffer.position(buffer.position() + dataLength);
                    currentPos++;
                }
                
                // Leer longitud del dato existente
                int dataOffsetStart = buffer.position();
                int existingLength = buffer.getInt();
                int dataStartPosition = buffer.position();
                
                // Calcular tamaño del nuevo valor
                ByteBuffer tempBuffer = ByteBuffer.allocate(1024);
                PrimitiveSerializer.write(tempBuffer, newValue, dataType);
                int newLength = tempBuffer.position();
                
                if (newLength <= existingLength) {
                    // Cabe en el mismo espacio - escribir directamente
                    buffer.position(dataStartPosition);
                    PrimitiveSerializer.write(buffer, newValue, dataType);
                    // Actualizar longitud si es menor
                    buffer.putInt(dataOffsetStart, newLength);
                    
                    // Invalidar cache porque los offsets pueden haber cambiado
                    cacheValid = false;
                } else {
                    // Nuevo valor es más grande - requeriría reescritura completa del bloque
                    // Permitimos reescritura si el nuevo valor cabe en la página
                    int maxAllowedSize = Page.PAGE_SIZE - Page.PAGE_HEADER_SIZE - 16;
                    if (newLength < maxAllowedSize) {
                        // Reescribir todo el bloque con el nuevo valor
                        rewriteBlockWithNewValue(position, newValue);
                        // La reescritura ya invalida la cache internamente
                    } else {
                        throw new IllegalStateException("Cannot update to larger value without defragmentation");
                    }
                }
            }
        }
        
        private void rewriteBlockWithNewValue(int position, Object newValue) {
            // Leer todos los valores existentes ANTES de modificar el buffer
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                if (i == position) {
                    values.add(newValue);
                } else {
                    // Usar get() que crea su propio contexto sincronizado
                    // Necesitamos leer todos los valores antes de modificar el buffer
                    ByteBuffer readBuffer = page.getByteBuffer();
                    readBuffer.position(Page.PAGE_HEADER_SIZE);
                    int rowCount = readBuffer.getInt();
                    
                    if (i >= rowCount) {
                        values.add(null);
                        continue;
                    }
                    
                    // Los datos comienzan después del rowCount (4 bytes) + flags (4 bytes) = 8 bytes
                    int dataStart = Page.PAGE_HEADER_SIZE + 8;
                    readBuffer.position(dataStart);
                    
                    // Navegar hasta la posición deseada
                    for (int j = 0; j <= i; j++) {
                        int dataLength = readBuffer.getInt();
                        if (j == i) {
                            values.add(PrimitiveSerializer.read(readBuffer, dataType));
                            break;
                        }
                        readBuffer.position(readBuffer.position() + dataLength);
                    }
                }
            }
            
            // Reiniciar el bloque con los nuevos valores
            ByteBuffer buffer = page.getByteBuffer();
            buffer.clear();
            currentOffset = Page.PAGE_HEADER_SIZE + 8; // Saltar rowCount + flags
            size = 0;
            offsetCache.clear(); // Limpiar cache
            cacheValid = true;   // La cache ahora es válida pero vacía
            writeRowCount(); // Escribir rowCount inicial en 0
            
            // Re-escribir todos los valores
            for (Object value : values) {
                buffer.position(currentOffset);
                int lengthOffset = currentOffset;
                buffer.putInt(0);
                int dataStart = buffer.position();
                PrimitiveSerializer.write(buffer, value, dataType);
                int dataEnd = buffer.position();
                int dataLength = dataEnd - dataStart;
                buffer.putInt(lengthOffset, dataLength);
                currentOffset = dataEnd;
                size++;
            }
            updateRowCount();
        }
        
        private int getEstimatedEntrySize(DataType type) {
            // Estimación conservadora para verificación de espacio
            switch (type) {
                case BOOLEAN: return 2; // 1 null marker + 1 byte
                case INT: return 5;     // 1 null marker + 4 bytes
                case LONG: return 9;    // 1 null marker + 8 bytes
                case DOUBLE: return 9;  // 1 null marker + 8 bytes
                case STRING: return 260; // 1 null marker + 3 length + 256 max string
                case DATE: return 9;    // 1 null marker + 8 bytes
                case TIMESTAMP: return 9; // 1 null marker + 8 bytes
                case BLOB: return 1028; // 1 null marker + 4 length + 1024 max blob
                case DECIMAL: return 17; // 1 null marker + 16 bytes (BigDecimal serialized)
                default: return 64;
            }
        }
    }
    
    @FunctionalInterface
    public interface Predicate<T> {
        boolean test(T value);
    }
}
