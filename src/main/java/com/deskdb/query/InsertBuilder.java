package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Row;
import java.util.HashMap;
import java.util.Map;

public class InsertBuilder {
    private final Table table;
    private final Map<String, Object> values = new HashMap<>();

    public InsertBuilder(Table table) {
        this.table = table;
    }

    public InsertBuilder value(String column, Object value) {
        values.put(column, value);
        return this;
    }

    public void execute() throws Exception {
        Row row = new Row(0, values);
        table.insert(row);
    }
}
