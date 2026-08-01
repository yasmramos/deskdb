package com.deskdb.query;

import com.deskdb.core.*;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the advanced fluent API with lambda expressions.
 */
@DisplayName("Advanced Fluent API Tests")
public class AdvancedFluentApiTest {

    private static DeskDB db;
    private static final String TEST_DIR = "target/test-advanced-fluent-api";

    @BeforeAll
    public static void setUp() throws Exception {
        // Clean up test directory
        File testDir = new File(TEST_DIR);
        if (testDir.exists()) {
            deleteDirectory(testDir);
        }
        testDir.mkdirs();
        
        // Initialize database using public factory method
        db = DeskDB.open(Path.of(TEST_DIR, "advanced-fluent-db"));
        
        // Create test table using existing API from tests
        // Using TableSchema approach compatible with current API
        Table usersTable = db.createTable("users");
        
        // Insert test data
        db.table("users")
            .insert()
            .value("id", 1).value("name", "Alice").value("age", 25).value("email", "alice@example.com").value("status", "active")
            .addRow()
            .value("id", 2).value("name", "Bob").value("age", 30).value("email", "bob@example.com").value("status", "active")
            .addRow()
            .value("id", 3).value("name", "Charlie").value("age", 35).value("email", "charlie@example.com").value("status", "inactive")
            .addRow()
            .value("id", 4).value("name", "Diana").value("age", 28).value("email", "diana@example.com").value("status", "active")
            .addRow()
            .value("id", 5).value("name", "Eve").value("age", 45).value("email", "eve@example.com").value("status", "inactive")
            .execute();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        // Clean up test directory
        deleteDirectory(new File(TEST_DIR));
    }

    @Test
    @DisplayName("Select with column projection")
    public void testSelectWithColumnProjection() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .columns("name", "email")
            .where("age").gt(25)
            .execute();

        assertNotNull(results);
        assertTrue(results.size() > 0);
        
        // Verify only selected columns are present
        Row firstRow = results.get(0);
        assertTrue(firstRow.getValues().containsKey("name"));
        assertTrue(firstRow.getValues().containsKey("email"));
        assertFalse(firstRow.getValues().containsKey("age"));
        assertFalse(firstRow.getValues().containsKey("status"));
    }

    @Test
    @DisplayName("Select with lambda predicate - simple condition")
    public void testSelectWithLambdaPredicateSimple() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("age").gt(30))
            .execute();

        assertNotNull(results);
        assertEquals(2, results.size()); // Charlie (35) and Eve (45)
        
        for (Row row : results) {
            Integer age = (Integer) row.get("age");
            assertTrue(age > 30);
        }
    }

    @Test
    @DisplayName("Select with lambda predicate - multiple conditions with AND")
    public void testSelectWithLambdaPredicateMultipleConditions() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user
                .field("age").gt(25)
                .and("status").eq("active"))
            .execute();

        assertNotNull(results);
        // Bob (30, active), Diana (28, active) - Alice is 25 (not > 25)
        assertEquals(2, results.size());
        
        for (Row row : results) {
            Integer age = (Integer) row.get("age");
            String status = (String) row.get("status");
            assertTrue(age > 25);
            assertEquals("active", status);
        }
    }

    @Test
    @DisplayName("Select with lambda predicate - complex conditions")
    public void testSelectWithLambdaPredicateComplex() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user
                .field("age").between(25, 35)
                .and("status").eq("active"))
            .orderBy("age")
            .execute();

        assertNotNull(results);
        // Alice (25), Bob (30), Diana (28) - all active and age between 25-35
        assertEquals(3, results.size());
        
        // Verify ordering
        for (int i = 0; i < results.size() - 1; i++) {
            Integer age1 = (Integer) results.get(i).get("age");
            Integer age2 = (Integer) results.get(i + 1).get("age");
            assertTrue(age1 <= age2);
        }
    }

    @Test
    @DisplayName("Select with lambda predicate - less than condition")
    public void testSelectWithLambdaPredicateLessThan() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("age").lt(30))
            .execute();

        assertNotNull(results);
        // Alice (25), Diana (28)
        assertEquals(2, results.size());
        
        for (Row row : results) {
            Integer age = (Integer) row.get("age");
            assertTrue(age < 30);
        }
    }

    @Test
    @DisplayName("Select with lambda predicate - not equals condition")
    public void testSelectWithLambdaPredicateNotEquals() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("status").ne("active"))
            .execute();

        assertNotNull(results);
        // Charlie (inactive), Eve (inactive)
        assertEquals(2, results.size());
        
        for (Row row : results) {
            String status = (String) row.get("status");
            assertEquals("inactive", status);
        }
    }

    @Test
    @DisplayName("Select with lambda predicate - greater than or equal")
    public void testSelectWithLambdaPredicateGte() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("age").gte(30))
            .orderByDesc("age")
            .execute();

        assertNotNull(results);
        // Bob (30), Charlie (35), Eve (45)
        assertEquals(3, results.size());
        
        // Verify descending order
        for (int i = 0; i < results.size() - 1; i++) {
            Integer age1 = (Integer) results.get(i).get("age");
            Integer age2 = (Integer) results.get(i + 1).get("age");
            assertTrue(age1 >= age2);
        }
    }

    @Test
    @DisplayName("Select with lambda predicate - less than or equal")
    public void testSelectWithLambdaPredicateLte() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("age").lte(28))
            .execute();

        assertNotNull(results);
        // Alice (25), Diana (28)
        assertEquals(2, results.size());
        
        for (Row row : results) {
            Integer age = (Integer) row.get("age");
            assertTrue(age <= 28);
        }
    }

    @Test
    @DisplayName("Select with pagination using lambda predicate")
    public void testSelectWithPaginationAndLambda() throws Exception {
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("age").gt(20))
            .orderBy("name")
            .limit(2)
            .offset(1)
            .execute();

        assertNotNull(results);
        assertTrue(results.size() <= 2);
    }

    @Test
    @DisplayName("Insert with fluent consumer API")
    public void testInsertWithFluentConsumer() throws Exception {
        int initialCount = db.table("users").select().execute().size();
        
        db.table("users")
            .insert()
            .values(user -> user
                .value("id", 100)
                .value("name", "Test User")
                .value("age", 33)
                .value("email", "test@example.com")
                .value("status", "active"))
            .execute();
        
        int finalCount = db.table("users").select().execute().size();
        assertEquals(initialCount + 1, finalCount);
        
        // Verify the inserted data
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("id").eq(100))
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("Test User", results.get(0).get("name"));
        assertEquals(33, results.get(0).get("age"));
    }

    @Test
    @DisplayName("Delete with lambda predicate")
    public void testDeleteWithLambdaPredicate() throws Exception {
        // First insert a test record to delete
        db.table("users")
            .insert()
            .value("id", 200)
            .value("name", "To Delete")
            .value("age", 99)
            .value("email", "delete@example.com")
            .value("status", "inactive")
            .execute();
        
        int initialCount = db.table("users").select().execute().size();
        
        // Delete using lambda predicate
        db.table("users")
            .delete()
            .where(user -> user.field("id").eq(200))
            .execute();
        
        int finalCount = db.table("users").select().execute().size();
        assertEquals(initialCount - 1, finalCount);
        
        // Verify deletion
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("id").eq(200))
            .execute();
        
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Update with lambda predicate in WHERE clause")
    public void testUpdateWithLambdaPredicateInWhere() throws Exception {
        // Update using lambda predicate for WHERE
        db.table("users")
            .update()
            .set("status", "premium")
            .where(user -> user.field("age").gt(40))
            .execute();
        
        // Verify update
        List<Row> results = db.table("users")
            .select()
            .where(user -> user.field("age").gt(40))
            .execute();
        
        assertTrue(results.size() > 0);
        for (Row row : results) {
            assertEquals("premium", row.get("status"));
        }
    }

    /**
     * Helper method to delete directory recursively.
     */
    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
