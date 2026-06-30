package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Row;
import com.deskdb.core.Transaction;
import java.util.HashMap;
import java.util.Map;

public class InsertBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private final Map<String, Object> values = new HashMap<>();

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
     * Inserts a map of values into the table.
     */
    public InsertBuilder insert(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            this.values.put(entry.getKey(), entry.getValue());
        }
        return this;
    }

    public InsertBuilder value(String column, Object value) {
        values.put(column, value);
        return this;
    }

    public void execute() throws Exception {
        execute(null);
    }
    
    public void execute(Transaction tx) throws Exception {
        Row row = new Row(0, values);
        Transaction transactionToUse = tx != null ? tx : this.transaction;
        if (transactionToUse != null) {
            transactionToUse.applyChange(tableName, 0, row);
        } else if (table != null) {
            table.insert(row);
        } else {
            throw new IllegalStateException("No table or transaction available for insert");
        }
    }
}
