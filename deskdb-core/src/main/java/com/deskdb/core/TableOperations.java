package com.deskdb.core;

import com.deskdb.query.SelectBuilder;
import com.deskdb.query.InsertBuilder;
import com.deskdb.query.UpdateBuilder;
import com.deskdb.query.DeleteBuilder;
import com.deskdb.query.HistoryBuilder;

public class TableOperations {
    private final DeskDB db;
    private final String tableName;
    private final Transaction transaction;

    public TableOperations(DeskDB db, String tableName) {
        this.db = db;
        this.tableName = tableName;
        this.transaction = null;
    }
    
    public TableOperations(DeskDB db, String tableName, Transaction transaction) {
        this.db = db;
        this.tableName = tableName;
        this.transaction = transaction;
    }

    public SelectBuilder select() {
        if (transaction != null) {
            return new SelectBuilder(transaction, tableName);
        }
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new SelectBuilder(table);
    }

    /**
     * Creates a HistoryBuilder for time-travel queries.
     * Usage: db.table("users").history().history(123L).asOf(timestamp).execute()
     * @return a new HistoryBuilder instance
     */
    public HistoryBuilder history() {
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new HistoryBuilder(table);
    }

    public InsertBuilder insert() {
        if (transaction != null) {
            return new InsertBuilder(transaction, tableName);
        }
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new InsertBuilder(table);
    }

    public UpdateBuilder update() {
        if (transaction != null) {
            return new UpdateBuilder(transaction, tableName);
        }
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new UpdateBuilder(table);
    }

    public DeleteBuilder delete() {
        if (transaction != null) {
            return new DeleteBuilder(transaction, tableName);
        }
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new DeleteBuilder(table);
    }

    /**
     * Restores soft-deleted rows.
     * Usage: db.table("users").restore().where("id").eq(123).execute()
     * @return an UpdateBuilder configured to restore soft-deleted rows
     */
    public UpdateBuilder restore() {
        UpdateBuilder builder = update();
        builder.set("deleted", false);
        builder.set("deletedAt", null);
        return builder;
    }

    // Helper para WHERE directo: db.table("x").where("col").is(val).select()
    public SelectBuilder.WhereCondition where(String column) {
        if (transaction != null) {
            SelectBuilder builder = new SelectBuilder(transaction, tableName);
            return builder.new WhereCondition(column, builder);
        }
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        SelectBuilder builder = new SelectBuilder(table);
        return builder.new WhereCondition(column, builder);
    }
    
    // Helper para WHERE directo con WhereCondition: db.table("x").whereCond("col").eq(val)
    public SelectBuilder.WhereCondition whereCond(String column) {
        return where(column);
    }
}
