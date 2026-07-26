package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Row;
import com.deskdb.core.Transaction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InsertBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private final List<Map<String, Object>> batchValues = new ArrayList<>();
    private final Map<String, Object> currentValues = new HashMap<>();

    public InsertBuilder(Table table) {
        this.table = table;
        this.transaction = null;
        this.tableName = null;
    }
    
    public InsertBuilder(Transaction transaction, String tableName) {
        this.table = null;
        this.transaction = transaction;
        this.tableName = tableName;
    }

    /**
     * Sets a value for the current row being built.
     */
    public InsertBuilder value(String column, Object value) {
        currentValues.put(column, value);
        return this;
    }

    /**
     * Adds the current row to the batch and clears current values for next row.
     */
    public InsertBuilder addRow() {
        batchValues.add(new HashMap<>(currentValues));
        currentValues.clear();
        return this;
    }

    /**
     * Inserts a single map of values (legacy compatibility).
     */
    public InsertBuilder insert(Map<String, Object> values) {
        batchValues.add(new HashMap<>(values));
        return this;
    }

    /**
     * Executes all accumulated rows in a single operation.
     * Automatically handles both single and batch inserts efficiently.
     */
    public void execute() throws Exception {
        execute(null);
    }
    
    /**
     * Executes all accumulated rows in a single operation with optional transaction.
     * Automatically handles both single and batch inserts efficiently.
     */
    public void execute(Transaction tx) throws Exception {
        if (batchValues.isEmpty() && !currentValues.isEmpty()) {
            addRow();
        }
        if (batchValues.isEmpty()) {
            return;
        }

        Transaction transactionToUse = tx != null ? tx : this.transaction;
        
        if (transactionToUse != null) {
            // Use provided or builder transaction
            for (Map<String, Object> values : batchValues) {
                Row row = new Row(0, values);
                transactionToUse.applyChange(tableName, 0, row);
            }
        } else if (table != null) {
            // Auto-commit: create implicit transaction for entire batch
            try (Transaction autoTx = table.getDb().beginTransaction()) {
                for (Map<String, Object> values : batchValues) {
                    Row row = new Row(0, values);
                    autoTx.applyChange(table.getName(), 0, row);
                }
                autoTx.commit();
            }
        } else {
            throw new IllegalStateException("No table or transaction available for insert");
        }
        
        batchValues.clear();
    }
}
