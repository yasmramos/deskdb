package com.deskdb.core;

import com.deskdb.query.SelectBuilder;
import com.deskdb.query.InsertBuilder;
import com.deskdb.query.UpdateBuilder;
import com.deskdb.query.DeleteBuilder;

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
}
