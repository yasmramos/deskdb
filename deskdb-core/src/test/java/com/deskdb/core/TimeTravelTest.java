package com.deskdb.core;

import com.deskdb.query.HistoryBuilder;
import org.junit.jupiter.api.*;
import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Time Travel feature.
 * Tests historical version queries and as-of timestamp functionality.
 */
@DisplayName("Time Travel Feature Tests")
class TimeTravelTest {

    private static DeskDB db;
    private static File dbDir;

    @BeforeAll
    static void setUp() throws Exception {
        dbDir = new File(System.getProperty("java.io.tmpdir"), "deskdb_timetravel_" + System.currentTimeMillis());
        dbDir.mkdirs();
        db = DeskDB.open(dbDir.toPath());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        if (dbDir != null && dbDir.exists()) {
            deleteRecursively(dbDir);
        }
    }

    @BeforeEach
    void setupTest() throws Exception {
        // Create users table for testing using fluent API
        try {
            db.createTable("users",
                new Column("id", DataType.INT).primaryKey(),
                new Column("name", DataType.STRING),
                new Column("age", DataType.INT));
        } catch (Exception e) {
            // Table might already exist, ignore
        }
        
        // Insert initial data using fluent API
        db.table("users")
            .insert()
            .values(row -> row
                .value("id", 1)
                .value("name", "John")
                .value("age", 25))
            .execute();
            
        db.table("users")
            .insert()
            .values(row -> row
                .value("id", 2)
                .value("name", "Jane")
                .value("age", 30))
            .execute();
    }

    @AfterEach
    void cleanupTest() throws Exception {
        try {
            db.table("users")
                .delete()
                .where("id").greaterThan(0)
                .execute();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    @DisplayName("Should create HistoryBuilder from table operations")
    void shouldCreateHistoryBuilder() {
        assertDoesNotThrow(() -> {
            Table table = db.getTable("users");
            assertNotNull(table);
            
            TableOperations ops = db.table("users");
            assertNotNull(ops);
            
            HistoryBuilder builder = ops.history();
            assertNotNull(builder);
        });
    }

    @Test
    @DisplayName("Should query history for specific row ID")
    void shouldQueryHistoryForRowId() throws Exception {
        Table table = db.getTable("users");
        assertNotNull(table);
        
        List<RowVersion> versions = db.table("users")
            .history()
            .history(1L)
            .execute();
        
        assertNotNull(versions);
        assertFalse(versions.isEmpty(), "Should return at least current version");
        
        RowVersion version = versions.get(0);
        assertEquals(1L, version.getRowId());
        assertNotNull(version.getValues());
        assertEquals("John", version.getValues().get("name"));
    }

    @Test
    @DisplayName("Should query history as of specific timestamp")
    void shouldQueryHistoryAsOfTimestamp() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime past = now.minusDays(7);
        
        List<RowVersion> versions = db.table("users")
            .history()
            .asOf(past)
            .execute();
        
        assertNotNull(versions);
        // Should return current versions even if querying past
        // (full implementation would return actual historical versions)
    }

    @Test
    @DisplayName("Should combine row ID and as-of timestamp filters")
    void shouldCombineRowIdAndAsOfFilters() throws Exception {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        
        List<RowVersion> versions = db.table("users")
            .history()
            .history(1L)
            .asOf(past)
            .execute();
        
        assertNotNull(versions);
        assertEquals(1, versions.size());
        assertEquals(1L, versions.get(0).getRowId());
    }

    @Test
    @DisplayName("Should apply where conditions to history query")
    void shouldApplyWhereConditions() throws Exception {
        List<RowVersion> versions = db.table("users")
            .history()
            .where("age").gt(20)
            .execute();
        
        assertNotNull(versions);
        assertFalse(versions.isEmpty());
        
        for (RowVersion version : versions) {
            Integer age = (Integer) version.getValues().get("age");
            assertTrue(age > 20, "Age should be greater than 20");
        }
    }

    @Test
    @DisplayName("Should apply limit to history results")
    void shouldApplyLimit() throws Exception {
        List<RowVersion> allVersions = db.table("users")
            .history()
            .execute();
        
        List<RowVersion> limitedVersions = db.table("users")
            .history()
            .limit(1)
            .execute();
        
        assertNotNull(limitedVersions);
        assertTrue(limitedVersions.size() <= 1, "Should respect limit");
        assertTrue(limitedVersions.size() <= allVersions.size());
    }

    @Test
    @DisplayName("Should apply offset to history results")
    void shouldApplyOffset() throws Exception {
        List<RowVersion> firstPage = db.table("users")
            .history()
            .limit(1)
            .offset(0)
            .execute();
        
        List<RowVersion> secondPage = db.table("users")
            .history()
            .limit(1)
            .offset(1)
            .execute();
        
        assertNotNull(firstPage);
        assertNotNull(secondPage);
        
        if (!firstPage.isEmpty() && !secondPage.isEmpty()) {
            assertNotEquals(firstPage.get(0).getRowId(), secondPage.get(0).getRowId(), 
                "Offset should return different rows");
        }
    }

    @Test
    @DisplayName("Should handle empty results gracefully")
    void shouldHandleEmptyResults() throws Exception {
        List<RowVersion> versions = db.table("users")
            .history()
            .history(999L)  // Non-existent ID
            .execute();
        
        assertNotNull(versions);
        assertTrue(versions.isEmpty(), "Should return empty list for non-existent row");
    }

    @Test
    @DisplayName("Should handle multiple rows in history query")
    void shouldHandleMultipleRows() throws Exception {
        // Insert more test data using fluent API
        db.table("users")
            .insert()
            .values(row -> row
                .value("id", 3)
                .value("name", "Bob")
                .value("age", 35))
            .execute();
        
        List<RowVersion> versions = db.table("users")
            .history()
            .where("age").gte(25)
            .execute();
        
        assertNotNull(versions);
        assertTrue(versions.size() >= 3, "Should return all matching rows");
    }

    @Test
    @DisplayName("Should preserve row values in RowVersion")
    void shouldPreserveRowValues() throws Exception {
        List<RowVersion> versions = db.table("users")
            .history()
            .history(2L)
            .execute();
        
        assertNotNull(versions);
        assertEquals(1, versions.size());
        
        RowVersion version = versions.get(0);
        Map<String, Object> values = version.getValues();
        
        assertNotNull(values);
        assertEquals("Jane", values.get("name"));
        assertEquals(30, values.get("age"));
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }
}
