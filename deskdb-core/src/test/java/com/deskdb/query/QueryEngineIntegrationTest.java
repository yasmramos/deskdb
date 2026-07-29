package com.deskdb.query;

import com.deskdb.core.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Query Engine components:
 * - Filter operations
 * - Basic query execution
 */
@DisplayName("Query Engine Integration Tests")
public class QueryEngineIntegrationTest {
    
    private Path tempDbPath;
    private DeskDB db;
    private Table table;
    
    @BeforeEach
    void setUp() throws Exception {
        tempDbPath = Files.createTempFile("query_test", ".deskdb");
        db = DeskDB.open(tempDbPath);
        
        // Create test table with schema
        db.createTable("products",
            new Column("id", DataType.LONG).primaryKey(),
            new Column("name", DataType.STRING),
            new Column("price", DataType.DOUBLE),
            new Column("quantity", DataType.INT),
            new Column("category", DataType.STRING),
            new Column("active", DataType.BOOLEAN)
        );
        
        table = db.getTable("products");
        
        // Insert test data
        insertProduct(db, 1L, "Laptop", 999.99, 50, "Electronics", true);
        insertProduct(db, 2L, "Mouse", 29.99, 200, "Electronics", true);
        insertProduct(db, 3L, "Keyboard", 79.99, 150, "Electronics", true);
        insertProduct(db, 4L, "Monitor", 299.99, 75, "Electronics", false);
        insertProduct(db, 5L, "Desk", 199.99, 30, "Furniture", true);
        insertProduct(db, 6L, "Chair", 149.99, 45, "Furniture", true);
        insertProduct(db, 7L, "Headphones", 89.99, 100, "Electronics", true);
        insertProduct(db, 8L, "Webcam", 59.99, 80, "Electronics", false);
        insertProduct(db, 9L, "Bookshelf", 129.99, 20, "Furniture", true);
        insertProduct(db, 10L, "Lamp", 39.99, 60, "Furniture", true);
        insertProduct(db, 11L, "Clean Code", 45.99, 100, "Books", true);
        insertProduct(db, 12L, "Design Patterns", 54.99, 75, "Books", false);
    }
    
    /**
     * Helper method to insert a product into a specific database instance.
     */
    protected static void insertProduct(DeskDB database, Long id, String name, Double price, 
                               Integer quantity, String category, Boolean active) throws Exception {
        database.table("products").insert()
            .value("id", id)
            .value("name", name)
            .value("price", price)
            .value("quantity", quantity)
            .value("category", category)
            .value("active", active)
            .execute();
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (tempDbPath != null && Files.exists(tempDbPath)) {
            Files.delete(tempDbPath);
        }
    }
    
}
