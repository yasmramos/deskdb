package com.deskdb.core;

import org.junit.jupiter.api.*;
import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Soft Delete and Restore feature.
 * Tests marking rows as deleted and restoring them.
 */
@DisplayName("Soft Delete and Restore Feature Tests")
class SoftDeleteRestoreTest {

    private static DeskDB db;
    private static File dbDir;

    @BeforeAll
    static void setUp() throws Exception {
        dbDir = new File(System.getProperty("java.io.tmpdir"), "deskdb_softdelete_" + System.currentTimeMillis());
        dbDir.mkdirs();
        db = new DeskDB(dbDir.toPath());
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
        // Insert test data using fluent API
        db.table("users")
            .insert()
            .values(user -> user
                .value("id", 1)
                .value("name", "John")
                .value("age", 25)
                .value("deleted", false))
            .execute();
            
        db.table("users")
            .insert()
            .values(user -> user
                .value("id", 2)
                .value("name", "Jane")
                .value("age", 30)
                .value("deleted", false))
            .execute();
            
        db.table("users")
            .insert()
            .values(user -> user
                .value("id", 3)
                .value("name", "Bob")
                .value("age", 35)
                .value("deleted", false))
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
    @DisplayName("Should perform soft delete on single row")
    void shouldSoftDeleteSingleRow() throws Exception {
        // Perform soft delete
        int deleted = db.table("users")
            .delete()
            .soft()
            .where("id").eq(1)
            .execute();
        
        assertEquals(1, deleted, "Should delete 1 row");

        // Verify row is marked as deleted
        List<Row> after = db.table("users")
            .select()
            .where("id").eq(1)
            .execute();
        
        assertNotNull(after);
        assertEquals(1, after.size());
        Map<String, Object> values = after.get(0).getValues();
        Boolean isDeleted = (Boolean) values.get("deleted");
        assertTrue(isDeleted != null && isDeleted, "Row should be marked as deleted");
    }

    @Test
    @DisplayName("Should restore soft-deleted row")
    void shouldRestoreSoftDeletedRow() throws Exception {
        // First soft delete a row
        db.table("users")
            .delete()
            .soft()
            .where("id").eq(2)
            .execute();

        // Restore the row
        int restored = db.table("users")
            .restore()
            .where("id").eq(2)
            .execute();
        
        assertEquals(1, restored, "Should restore 1 row");

        // Verify row is restored
        List<Row> restoredRow = db.table("users")
            .select()
            .where("id").eq(2)
            .execute();
        
        assertNotNull(restoredRow);
        assertEquals(1, restoredRow.size());
        Map<String, Object> values = restoredRow.get(0).getValues();
        Boolean deleted = (Boolean) values.get("deleted");
        assertFalse(deleted != null && deleted, "Row should be restored");
    }

    @Test
    @DisplayName("Should handle soft delete with no matching rows")
    void shouldHandleSoftDeleteWithNoMatches() throws Exception {
        int deleted = db.table("users")
            .delete()
            .soft()
            .where("id").eq(999)
            .execute();
        
        assertEquals(0, deleted, "Should return 0 for non-matching rows");
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
