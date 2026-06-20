package com.deskdb.core;

import com.deskdb.index.BTree;
import com.deskdb.query.QueryOptimizer;
import com.deskdb.storage.DataFile;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Representa una tabla en DeskDB con soporte para índices.
 */
public class Table {
    private final String name;
    private final List<Column> columns;
    private final Map<String, BTree> indexes;
    private BTree primaryKeyIndex;
    private final DataFile dataFile;
    private long nextRowId = 1;

    public Table(String name, List<Column> columns, String dbPath) throws IOException {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.indexes = new HashMap<>();
        this.dataFile = new DataFile(dbPath + "." + name + ".dat", columns);
        
        // Crear índice automático para PRIMARY KEY
        for (Column col : columns) {
            if (col.isPrimaryKey()) {
                primaryKeyIndex = new BTree("pk_" + name, 4);
                indexes.put(col.getName(), primaryKeyIndex);
            }
        }
        
        try {
            loadMetadata();
        } catch (ClassNotFoundException e) {
            throw new IOException("Error cargando metadatos de la tabla", e);
        }
    }

    private void loadMetadata() throws IOException, ClassNotFoundException {
        File metaFile = new File(dataFile.getFilePath() + ".meta");
        if (metaFile.exists()) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(metaFile))) {
                nextRowId = in.readLong();
                int indexCount = in.readInt();
                for (int i = 0; i < indexCount; i++) {
                    String idxName = in.readUTF();
                    BTree idx = new BTree(idxName, 4);
                    idx.load(in);
                    if (idxName.startsWith("pk_")) {
                        primaryKeyIndex = idx;
                    }
                    indexes.put(idxName.replaceFirst("pk_|idx_", ""), idx);
                }
            }
        }
    }

    public void saveMetadata() throws IOException {
        File metaFile = new File(dataFile.getFilePath() + ".meta");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(metaFile))) {
            out.writeLong(nextRowId);
            out.writeInt(indexes.size());
            for (Map.Entry<String, BTree> entry : indexes.entrySet()) {
                out.writeUTF(entry.getKey());
                entry.getValue().persist(out);
            }
        }
    }

    /**
     * Crea un índice en una columna específica.
     */
    public Table createIndex(String indexName, String columnName) throws IOException {
        Column column = columns.stream()
            .filter(c -> c.getName().equals(columnName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Columna no encontrada: " + columnName));
        
        BTree index = new BTree("idx_" + indexName, 4);
        indexes.put(columnName, index);
        
        // Indexar filas existentes
        List<Row> allRows = scanAll();
        for (Row row : allRows) {
            Object value = row.get(columnName);
            if (value != null && value instanceof Comparable) {
                index.insert((Comparable) value, row.getRowId());
            }
        }
        
        saveMetadata();
        return this;
    }

    /**
     * Crea un índice único en una columna.
     */
    public Table createUniqueIndex(String indexName, String columnName) throws IOException {
        Column column = columns.stream()
            .filter(c -> c.getName().equals(columnName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Columna no encontrada: " + columnName));
        
        column.unique();
        return createIndex(indexName, columnName);
    }

    /**
     * Inserta una fila con indexación automática.
     */
    public void insert(Map<String, Object> values) throws IOException {
        // Validar unicidad si aplica
        for (Column col : columns) {
            if (col.isUnique() && indexes.containsKey(col.getName())) {
                Object value = values.get(col.getName());
                if (value != null) {
                    BTree index = indexes.get(col.getName());
                    List<Long> existing = index.search((Comparable) value);
                    if (!existing.isEmpty()) {
                        throw new UniqueConstraintViolationException(
                            "Violación de unicidad en columna: " + col.getName());
                    }
                }
            }
        }

        long rowId = nextRowId++;
        Row row = new Row(rowId, values);
        
        // Guardar en archivo de datos
        dataFile.write(row);
        
        // Indexar cada columna que tenga índice
        for (Map.Entry<String, BTree> entry : indexes.entrySet()) {
            String colName = entry.getKey();
            Object value = values.get(colName);
            if (value != null && value instanceof Comparable) {
                entry.getValue().insert((Comparable) value, rowId);
            }
        }
        
        saveMetadata();
    }

    /**
     * Busca filas por valor exacto en una columna indexada.
     */
    public List<Row> find(String column, Object value) throws IOException {
        BTree index = indexes.get(column);
        if (index != null && value instanceof Comparable) {
            List<Long> rowIds = index.search((Comparable) value);
            return dataFile.readRows(rowIds);
        }
        // Fallback: scan completo
        return scanAll().stream()
            .filter(row -> {
                Object val = row.get(column);
                return val != null && val.equals(value);
            })
            .collect(Collectors.toList());
    }

    /**
     * Busca filas por rango en una columna indexada.
     */
    public List<Row> findRange(String column, Object from, Object to) throws IOException {
        BTree index = indexes.get(column);
        if (index != null && from instanceof Comparable && to instanceof Comparable) {
            List<Long> rowIds = index.rangeSearch((Comparable) from, (Comparable) to);
            return dataFile.readRows(rowIds);
        }
        // Fallback: scan completo
        return scanAll().stream()
            .filter(row -> {
                Object val = row.get(column);
                return val != null && val instanceof Comparable
                    && ((Comparable) val).compareTo(from) >= 0
                    && ((Comparable) val).compareTo(to) <= 0;
            })
            .collect(Collectors.toList());
    }

    /**
     * Busca filas aplicando filtros y usando índices si están disponibles.
     */
    public List<Row> select(List<Filter> filters) throws IOException {
        if (filters == null || filters.isEmpty()) {
            return scanAll();
        }

        // Usar el optimizador para decidir si usar índice
        QueryOptimizer optimizer = new QueryOptimizer();
        QueryOptimizer.QueryPlan plan = optimizer.optimize(this, filters);

        if (plan.isUseIndex() && plan.getIndex().isPresent()) {
            // Ejecutar consulta usando el índice seleccionado
            BTree index = plan.getIndex().get();
            Filter filter = plan.getFilter();
            List<Long> rowIds;

            switch (filter.getOperator()) {
                case EQ:
                    rowIds = index.search((Comparable) filter.getValue());
                    break;
                case GT:
                    // Para >, necesitamos un valor ligeramente mayor, usamos rangeSearch desde el valor
                    rowIds = index.rangeSearch((Comparable) filter.getValue(), null);
                    break;
                case LT:
                    rowIds = index.rangeSearch(null, (Comparable) filter.getValue());
                    break;
                case GTE:
                case LTE:
                    // Para >= y <=, incluimos el valor exacto en el rango
                    // Esto requiere una lógica más compleja, por ahora fallback a scan
                    return applyFilters(scanAll(), filters);
                default:
                    // Fallback a scan completo si el tipo no es soportado directamente por el índice
                    return applyFilters(scanAll(), filters);
            }

            // Leer las filas y aplicar el resto de filtros si los hay
            List<Row> results = dataFile.readRows(rowIds);
            if (filters.size() > 1) {
                // Aplicar filtros adicionales en memoria
                return applyFilters(results, filters);
            }
            return results;
        } else {
            // No hay índice útil, hacer scan completo y filtrar
            return applyFilters(scanAll(), filters);
        }
    }

    /**
     * Aplica una lista de filtros a un conjunto de filas.
     */
    private List<Row> applyFilters(List<Row> rows, List<Filter> filters) {
        return rows.stream()
            .filter(row -> {
                for (Filter filter : filters) {
                    if (!filter.apply(row)) {
                        return false;
                    }
                }
                return true;
            })
            .collect(Collectors.toList());
    }

    /**
     * Escaneo completo de todas las filas.
     */
    public List<Row> scanAll() throws IOException {
        return dataFile.readAll();
    }

    /**
     * Actualiza filas que coinciden con un filtro.
     */
    public int update(Filter filter, Map<String, Object> newValues) throws IOException {
        List<Row> matching = scanAll().stream()
            .filter(row -> filter.apply(row))
            .collect(Collectors.toList());
        
        for (Row row : matching) {
            // Actualizar valores
            for (Map.Entry<String, Object> entry : newValues.entrySet()) {
                row.set(entry.getKey(), entry.getValue());
            }
            
            // Re-indexar si es necesario
            for (Map.Entry<String, BTree> entry : indexes.entrySet()) {
                String colName = entry.getKey();
                if (newValues.containsKey(colName)) {
                    BTree index = entry.getValue();
                    Object oldValue = row.get(colName);
                    Object newValue = newValues.get(colName);
                    if (oldValue != null && oldValue instanceof Comparable) {
                        index.delete((Comparable) oldValue, row.getRowId());
                    }
                    if (newValue != null && newValue instanceof Comparable) {
                        index.insert((Comparable) newValue, row.getRowId());
                    }
                }
            }
            
            dataFile.write(row); // Re-escribir fila actualizada
        }
        
        saveMetadata();
        return matching.size();
    }

    /**
     * Elimina filas que coinciden con un filtro.
     */
    public int delete(Filter filter) throws IOException {
        List<Row> matching = scanAll().stream()
            .filter(row -> filter.apply(row))
            .collect(Collectors.toList());
        
        for (Row row : matching) {
            // Eliminar de índices
            for (BTree index : indexes.values()) {
                for (String colName : indexes.keySet()) {
                    Object value = row.get(colName);
                    if (value != null && value instanceof Comparable) {
                        index.delete((Comparable) value, row.getRowId());
                    }
                }
            }
            dataFile.delete(row.getRowId());
        }
        
        saveMetadata();
        return matching.size();
    }

    public String getName() { return name; }
    public List<Column> getColumns() { return new ArrayList<>(columns); }
    public boolean hasIndex(String column) { return indexes.containsKey(column); }
    public BTree getIndex(String column) { return indexes.get(column); }
    public long size() throws IOException { return dataFile.count(); }
}
