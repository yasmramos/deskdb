package com.deskdb.core;

import com.deskdb.query.*;
import com.deskdb.index.BTree;
import com.deskdb.storage.DataFile;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Table {
    private final String name;
    private final List<Column> columns;
    private final DataFile dataFile;
    private final Map<String, BTree> indexes = new HashMap<>();
    private final Map<String, String> columnToIndex = new HashMap<>();
    private long nextRowId = 1;
    private final Object lock = new Object();

    public Table(String name, List<Column> columns, String dbPath) throws IOException {
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.dataFile = new DataFile(dbPath + "." + name + ".dat", this.columns);
        
        for (Column col : columns) {
            if (col.isPrimaryKey()) {
                createIndex("pk_" + name, col.getName());
            }
        }
    }

    public String getName() { return name; }
    public List<Column> getColumns() { return columns; }
    
    private boolean hasColumn(String columnName) {
        for (Column c : columns) {
            if (c.getName().equals(columnName)) return true;
        }
        return false;
    }

    public void createIndex(String indexName, String columnName) {
        if (!hasColumn(columnName)) {
            throw new IllegalArgumentException("Columna no existe: " + columnName);
        }
        BTree btree = new BTree(indexName, 4);
        indexes.put(indexName, btree);
        columnToIndex.put(columnName, indexName);
        
        try {
            List<Row> allRows = dataFile.readAll();
            for (Row row : allRows) {
                Object val = row.get(columnName);
                if (val != null) {
                    btree.insert((Comparable) val, row.getRowId());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error al re-indexar", e);
        }
    }

    public boolean hasIndex(String columnName) {
        return columnToIndex.containsKey(columnName);
    }
    
    public BTree getIndex(String columnName) {
        String idxName = columnToIndex.get(columnName);
        return (idxName != null) ? indexes.get(idxName) : null;
    }

    public void insert(Row row) throws IOException {
        synchronized (lock) {
            long rowId = dataFile.write(row);
            
            for (Map.Entry<String, String> entry : columnToIndex.entrySet()) {
                String colName = entry.getKey();
                Object val = row.get(colName);
                if (val != null) {
                    indexes.get(entry.getValue()).insert((Comparable) val, rowId);
                }
            }
        }
    }

    public List<Row> select(List<Filter> filters) throws IOException {
        if (filters == null || filters.isEmpty()) {
            return dataFile.readAll();
        }

        QueryOptimizer optimizer = new QueryOptimizer();
        Query query = new Query(name, filters, null, -1, 0, null, true);
        QueryPlan plan = optimizer.optimize(query, this);

        Optional<BTree> indexOpt = Optional.empty();
        Filter bestFilter = null;
        
        for (Filter f : filters) {
            if (hasIndex(f.getColumn())) {
                indexOpt = Optional.of(getIndex(f.getColumn()));
                bestFilter = f;
                break;
            }
        }

        if (indexOpt.isPresent() && bestFilter != null) {
            BTree index = indexOpt.get();
            List<Long> rowIds;

            if (bestFilter.getOperator() == Filter.Operator.EQ) {
                rowIds = index.search((Comparable) bestFilter.getValue());
            } else {
                rowIds = dataFile.readAll().stream()
                    .filter(r -> bestFilter.matches(r))
                    .map(Row::getRowId)
                    .collect(Collectors.toList());
            }

            List<Row> result = new ArrayList<>();
            for (long id : rowIds) {
                Row r = dataFile.read(id);
                if (r != null && matchesAllFilters(r, filters)) {
                    result.add(r);
                }
            }
            return result;
        } else {
            return dataFile.readAll().stream()
                .filter(r -> matchesAllFilters(r, filters))
                .collect(Collectors.toList());
        }
    }

    private boolean matchesAllFilters(Row row, List<Filter> filters) {
        for (Filter f : filters) {
            if (!f.matches(row)) return false;
        }
        return true;
    }

    public void update(long rowId, Map<String, Object> values) throws IOException {
        synchronized (lock) {
            Row oldRow = dataFile.read(rowId);
            if (oldRow == null) return;
            
            Map<String, Object> newValues = new HashMap<>(oldRow.getValues());
            newValues.putAll(values);
            Row newRow = new Row(rowId, newValues);
            
            for (String colName : columnToIndex.keySet()) {
                if (values.containsKey(colName) || oldRow.get(colName) != null) {
                    BTree idx = getIndex(colName);
                    Object oldVal = oldRow.get(colName);
                    Object newVal = newRow.get(colName);
                    
                    if (oldVal != null) idx.delete((Comparable) oldVal, rowId);
                    if (newVal != null) idx.insert((Comparable) newVal, rowId);
                }
            }
            
            dataFile.write(newRow);
        }
    }

    public void delete(long rowId) throws IOException {
        synchronized (lock) {
            Row row = dataFile.read(rowId);
            if (row == null) return;
            
            for (Map.Entry<String, String> entry : columnToIndex.entrySet()) {
                Object val = row.get(entry.getKey());
                if (val != null) {
                    indexes.get(entry.getValue()).delete((Comparable) val, rowId);
                }
            }
            
            dataFile.delete(rowId);
        }
    }
    
    public long size() throws IOException {
        return dataFile.count();
    }
    
    public void close() throws IOException {
        dataFile.close();
    }
}
