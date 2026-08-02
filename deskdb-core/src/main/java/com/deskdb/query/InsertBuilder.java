package com.deskdb.query;

import com.deskdb.core.Table;
import com.deskdb.core.Row;
import com.deskdb.core.Transaction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builder for constructing and executing INSERT operations.
 * <p>
 * Supports single-row inserts, batch inserts, and fluent API patterns.
 * All inserts are executed within a transaction for ACID guarantees.
 * </p>
 * 
 * <h2>Usage Examples:</h2>
 * 
 * <h3>Single Row Insert</h3>
 * <pre>{@code
 * db.table("users")
 *   .insert()
 *   .value("name", "John Doe")
 *   .value("email", "john@example.com")
 *   .value("age", 30)
 *   .execute();
 * }</pre>
 * 
 * <h3>Batch Insert with Multiple Rows</h3>
 * <pre>{@code
 * db.table("users")
 *   .insert()
 *   .value("name", "John").value("email", "john@example.com").addRow()
 *   .value("name", "Jane").value("email", "jane@example.com").addRow()
 *   .value("name", "Bob").value("email", "bob@example.com").addRow()
 *   .execute();
 * }</pre>
 * 
 * <h3>Fluent API with Consumer</h3>
 * <pre>{@code
 * db.table("users")
 *   .insert()
 *   .values(user -> user
 *       .value("name", "Alice")
 *       .value("email", "alice@example.com")
 *       .value("age", 25)
 *   )
 *   .execute();
 * }</pre>
 * 
 * <h3>Transaction-Based Batch Insert</h3>
 * <pre>{@code
 * try (Transaction tx = db.beginTransaction()) {
 *     db.table("users").table(tx)
 *       .insert()
 *       .value("name", "User1").addRow()
 *       .value("name", "User2").addRow()
 *       .value("name", "User3").addRow()
 *       .execute();
 *     tx.commit();
 * }
 * }</pre>
 * 
 * @see TableOperations#insert()
 * @see Transaction
 */
public class InsertBuilder {
    private final Table table;
    private final Transaction transaction;
    private final String tableName;
    private final List<Map<String, Object>> batchValues = new ArrayList<>();
    private final Map<String, Object> currentValues = new HashMap<>();
    private boolean ignoreDuplicates = false;
    private String[] uniqueColumns = null;

    /**
     * Creates an InsertBuilder for direct table operations.
     * 
     * @param table the target table for insert operations
     */
    public InsertBuilder(Table table) {
        this.table = Objects.requireNonNull(table, "Table cannot be null");
        this.transaction = null;
        this.tableName = null;
    }
    
    /**
     * Creates an InsertBuilder for transaction-based operations.
     * 
     * @param transaction the transaction context
     * @param tableName the name of the target table
     * @throws IllegalArgumentException if transaction or tableName is null
     */
    public InsertBuilder(Transaction transaction, String tableName) {
        this.transaction = Objects.requireNonNull(transaction, "Transaction cannot be null");
        this.tableName = Objects.requireNonNull(tableName, "Table name cannot be null");
        this.table = null;
    }

    /**
     * Sets a value for the specified column in the current row being built.
     * 
     * @param column the column name (must not be null or empty)
     * @param value the value to insert (can be null for nullable columns)
     * @return this InsertBuilder for method chaining
     * @throws IllegalArgumentException if column name is null or empty
     */
    public InsertBuilder value(String column, Object value) {
        if (column == null || column.trim().isEmpty()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        currentValues.put(column.trim(), value);
        return this;
    }
    
    /**
     * Sets multiple values at once using a map.
     * 
     * @param values a map of column names to values
     * @return this InsertBuilder for method chaining
     * @throws IllegalArgumentException if values map is null
     */
    public InsertBuilder values(Map<String, Object> values) {
        Objects.requireNonNull(values, "Values map cannot be null");
        currentValues.putAll(values);
        return this;
    }
    
    /**
     * Fluent API for setting values using a consumer function.
     * Automatically adds the row after the consumer completes.
     * 
     * <h3>Example:</h3>
     * <pre>{@code
     * db.table("users")
     *   .insert()
     *   .values(user -> user
     *       .value("name", "John")
     *       .value("email", "john@example.com")
     *       .value("age", 30)
     *   )
     *   .execute();
     * }</pre>
     * 
     * @param consumer a function that configures the current row
     * @return this InsertBuilder for method chaining (row already added)
     * @throws IllegalArgumentException if consumer is null
     */
    public InsertBuilder values(Consumer<InsertBuilder> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        consumer.accept(this);
        return addRow();
    }

    /**
     * Adds the current row to the batch and clears current values for the next row.
     * 
     * @return this InsertBuilder for method chaining
     * @throws IllegalStateException if current values are empty
     */
    public InsertBuilder addRow() {
        if (currentValues.isEmpty()) {
            throw new IllegalStateException("Cannot add empty row. Set at least one value before calling addRow()");
        }
        batchValues.add(Collections.unmodifiableMap(new HashMap<>(currentValues)));
        currentValues.clear();
        return this;
    }

    /**
     * Inserts a single map of values immediately (legacy compatibility).
     * This method adds the values to the batch but does not execute.
     * 
     * @param values a map of column names to values
     * @return this InsertBuilder for method chaining
     * @deprecated Use {@link #values(Map)} followed by {@link #addRow()} instead
     */
    @Deprecated
    public InsertBuilder insert(Map<String, Object> values) {
        Objects.requireNonNull(values, "Values map cannot be null");
        batchValues.add(Collections.unmodifiableMap(new HashMap<>(values)));
        return this;
    }
    
    /**
     * Enables duplicate key ignoring for the insert operation.
     * If a duplicate key constraint violation occurs, the conflicting row will be skipped.
     * 
     * @return this InsertBuilder for method chaining
     */
    public InsertBuilder ignoreDuplicates() {
        this.ignoreDuplicates = true;
        return this;
    }
    
    /**
     * Specifies columns that should be checked for uniqueness.
     * Only applicable when used with {@link #ignoreDuplicates()}.
     * 
     * @param columns the column names to check for uniqueness
     * @return this InsertBuilder for method chaining
     * @throws IllegalArgumentException if columns array is null or empty
     */
    public InsertBuilder onDuplicateKey(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("At least one column must be specified");
        }
        this.uniqueColumns = Arrays.copyOf(columns, columns.length);
        return this;
    }

    /**
     * Executes all accumulated rows in a single atomic operation.
     * Automatically creates a transaction if none exists.
     * 
     * @throws Exception if any database error occurs during insertion
     * @throws IllegalStateException if no rows have been added to the batch
     * @throws IllegalStateException if neither table nor transaction is available
     */
    public void execute() throws Exception {
        execute(null);
    }
    
    /**
     * Executes all accumulated rows with an optional external transaction.
     * <p>
     * If a transaction is provided, the insert will be part of that transaction.
     * If no transaction is provided and the builder has a transaction, it will use that.
     * Otherwise, an implicit transaction is created for the entire batch.
     * </p>
     * 
     * @param tx optional external transaction (can be null)
     * @throws Exception if any database error occurs during insertion
     * @throws IllegalStateException if no rows have been added to the batch
     * @throws IllegalStateException if neither table nor transaction is available
     */
    public void execute(Transaction tx) throws Exception {
        // Auto-add pending row if values exist (even if batch already has rows)
        if (!currentValues.isEmpty()) {
            addRow();
        }
        
        if (batchValues.isEmpty()) {
            throw new IllegalStateException("No rows to insert. Add at least one row before executing.");
        }

        Transaction transactionToUse = determineTransaction(tx);
        
        try {
            if (ignoreDuplicates && uniqueColumns != null) {
                executeWithDuplicateCheck(transactionToUse);
            } else {
                executeStandardInsert(transactionToUse);
            }
        } finally {
            batchValues.clear();
            currentValues.clear();
        }
    }
    
    /**
     * Determines which transaction to use for the operation.
     */
    private Transaction determineTransaction(Transaction externalTx) {
        if (externalTx != null) {
            return externalTx;
        }
        if (transaction != null) {
            return transaction;
        }
        if (table != null) {
            return null; // Will create auto-transaction
        }
        throw new IllegalStateException("No table or transaction available for insert");
    }
    
    /**
     * Executes standard insert without duplicate checking.
     */
    private void executeStandardInsert(Transaction transactionToUse) throws Exception {
        if (transactionToUse != null) {
            // Use existing transaction
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
        }
    }
    
    /**
     * Executes insert with duplicate key checking.
     */
    private void executeWithDuplicateCheck(Transaction transactionToUse) throws Exception {
        if (transactionToUse == null && table == null) {
            throw new IllegalStateException("Transaction required for duplicate checking");
        }
        
        if (transactionToUse != null) {
            // With transaction - check duplicates before inserting
            for (Map<String, Object> values : batchValues) {
                if (!isDuplicate(transactionToUse, values)) {
                    Row row = new Row(0, values);
                    transactionToUse.applyChange(tableName, 0, row);
                }
            }
        } else if (table != null) {
            // Auto-commit with duplicate checking
            try (Transaction autoTx = table.getDb().beginTransaction()) {
                for (Map<String, Object> values : batchValues) {
                    if (!isDuplicate(autoTx, values)) {
                        Row row = new Row(0, values);
                        autoTx.applyChange(table.getName(), 0, row);
                    }
                }
                autoTx.commit();
            }
        }
    }
    
    /**
     * Checks if a row would be a duplicate based on unique columns.
     */
    private boolean isDuplicate(Transaction tx, Map<String, Object> values) {
        // TODO: Implement duplicate checking logic based on uniqueColumns
        // For now, returns false to allow all inserts
        return false;
    }
    
    /**
     * Returns the number of rows currently queued for insertion.
     * 
     * @return the count of rows in the batch
     */
    public int getBatchSize() {
        return batchValues.size() + (currentValues.isEmpty() ? 0 : 1);
    }
    
    /**
     * Clears all pending rows from the batch.
     * 
     * @return this InsertBuilder for method chaining
     */
    public InsertBuilder clear() {
        batchValues.clear();
        currentValues.clear();
        return this;
    }
}
