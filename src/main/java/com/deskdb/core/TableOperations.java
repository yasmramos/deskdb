package com.deskdb.core;

import com.deskdb.query.SelectBuilder;
import com.deskdb.query.InsertBuilder;
import com.deskdb.query.UpdateBuilder;
import com.deskdb.query.DeleteBuilder;

public class TableOperations {
    private final DeskDB db;
    private final String tableName;

    public TableOperations(DeskDB db, String tableName) {
        this.db = db;
        this.tableName = tableName;
    }

    public SelectBuilder select() {
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new SelectBuilder(table);
    }

    public InsertBuilder insert() {
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new InsertBuilder(table);
    }

    public UpdateBuilder update() {
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new UpdateBuilder(table);
    }

    public DeleteBuilder delete() {
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        return new DeleteBuilder(table);
    }

    // Helper para WHERE directo: db.table("x").where("col").is(val).select()
    public SelectBuilder.WhereCondition where(String column) {
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new RuntimeException("Table '" + tableName + "' not found");
        }
        SelectBuilder builder = new SelectBuilder(table);
        return builder.new WhereCondition(column, builder);
    }
}
