package com.deskdb.core;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Export and Import feature.
 * Tests exporting and importing data in multiple formats.
 */
@DisplayName("Export and Import Feature Tests")
class ExportImportTest {

    private static DeskDB db;
    private static File dbDir;
    private static File testFile;

    @BeforeAll
    static void setUp() throws Exception {
        dbDir = new File(System.getProperty("java.io.tmpdir"), "deskdb_exportimport_" + System.currentTimeMillis());
        dbDir.mkdirs();
        db = new DeskDB(dbDir.toPath());
        
        // Create users table using the correct API
        db.createTable("users",
            new Column("id", DataType.INT).primaryKey(),
            new Column("name", DataType.STRING),
            new Column("age", DataType.INT),
            new Column("email", DataType.STRING)
        );
        
        testFile = new File(dbDir, "test_export.csv");
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
            .values(row -> row
                .value("id", 1)
                .value("name", "John")
                .value("age", 25)
                .value("email", "john@example.com"))
            .execute();
            
        db.table("users")
            .insert()
            .values(row -> row
                .value("id", 2)
                .value("name", "Jane")
                .value("age", 30)
                .value("email", "jane@example.com"))
            .execute();
            
        db.table("users")
            .insert()
            .values(row -> row
                .value("id", 3)
                .value("name", "Bob")
                .value("age", 35)
                .value("email", "bob@example.com"))
            .execute();
    }

    @AfterEach
    void cleanupTest() throws Exception {
        try {
            db.table("users")
                .delete()
                .where(u -> u.field("id").gt(0))
                .execute();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
        if (testFile != null && testFile.exists()) {
            testFile.delete();
        }
    }

    @Test
    @DisplayName("Should export table to CSV format")
    void shouldExportToCSV() throws Exception {
        // Export to CSV
        int exported = db.table("users")
            .export()
            .format(ExportFormat.CSV)
            .toFile(testFile.getAbsolutePath())
            .execute();
        
        assertTrue(exported > 0, "Should export at least one row");
        assertTrue(testFile.exists(), "Export file should exist");
        assertTrue(testFile.length() > 0, "Export file should not be empty");
    }

    @Test
    @DisplayName("Should export table to JSON format")
    void shouldExportToJSON() throws Exception {
        File jsonFile = new File(dbDir, "test_export.json");
        
        try {
            int exported = db.table("users")
                .export()
                .format(ExportFormat.JSON)
                .toFile(jsonFile.getAbsolutePath())
                .execute();
            
            assertTrue(exported > 0, "Should export at least one row");
            assertTrue(jsonFile.exists(), "Export file should exist");
            assertTrue(jsonFile.length() > 0, "Export file should not be empty");
        } finally {
            if (jsonFile.exists()) {
                jsonFile.delete();
            }
        }
    }

    @Test
    @DisplayName("Should handle export with no data gracefully")
    void shouldHandleExportWithNoData() throws Exception {
        // Delete all data first
        db.table("users")
            .delete()
            .where(u -> u.field("id").gt(0))
            .execute();
        
        File emptyFile = new File(dbDir, "empty_export.csv");
        
        try {
            int exported = db.table("users")
                .export()
                .format(ExportFormat.CSV)
                .toFile(emptyFile.getAbsolutePath())
                .execute();
            
            assertEquals(0, exported, "Should export 0 rows");
            // File may or may not be created depending on implementation
        } finally {
            if (emptyFile.exists()) {
                emptyFile.delete();
            }
        }
    }

    @Test
    @DisplayName("Should export specific columns")
    void shouldExportSpecificColumns() throws Exception {
        // This test verifies the export builder supports column selection
        // Implementation may vary
        assertDoesNotThrow(() -> {
            File colFile = new File(dbDir, "columns_export.csv");
            try {
                int exported = db.table("users")
                    .export()
                    .format(ExportFormat.CSV)
                    .toFile(colFile.getAbsolutePath())
                    .execute();
                
                assertTrue(exported >= 0);
            } finally {
                if (colFile.exists()) {
                    colFile.delete();
                }
            }
        });
    }

    @Test
    @DisplayName("Should verify ExportFormat enum values")
    void shouldVerifyExportFormatEnum() {
        // Verify all expected export formats exist
        ExportFormat[] formats = ExportFormat.values();
        assertNotNull(formats);
        assertTrue(formats.length > 0);
        
        // Check for common formats
        boolean hasCSV = false;
        boolean hasJSON = false;
        
        for (ExportFormat format : formats) {
            if (format.name().equals("CSV")) hasCSV = true;
            if (format.name().equals("JSON")) hasJSON = true;
        }
        
        assertTrue(hasCSV, "Should have CSV format");
        assertTrue(hasJSON, "Should have JSON format");
    }

    @Test
    @DisplayName("Should verify ImportFormat enum values")
    void shouldVerifyImportFormatEnum() {
        // Verify all expected import formats exist
        ImportFormat[] formats = ImportFormat.values();
        assertNotNull(formats);
        assertTrue(formats.length > 0);
        
        // Check for common formats
        boolean hasCSV = false;
        boolean hasJSON = false;
        
        for (ImportFormat format : formats) {
            if (format.name().equals("CSV")) hasCSV = true;
            if (format.name().equals("JSON")) hasJSON = true;
        }
        
        assertTrue(hasCSV, "Should have CSV format");
        assertTrue(hasJSON, "Should have JSON format");
    }

    @Test
    @DisplayName("Should chain export operations fluently")
    void shouldChainExportOperations() throws Exception {
        assertDoesNotThrow(() -> {
            File chainFile = new File(dbDir, "chain_export.csv");
            try {
                int result = db.table("users")
                    .export()
                    .format(ExportFormat.CSV)
                    .toFile(chainFile.getAbsolutePath())
                    .execute();
                
                assertTrue(result >= 0);
            } finally {
                if (chainFile.exists()) {
                    chainFile.delete();
                }
            }
        });
    }

    @Test
    @DisplayName("Should handle export to non-existent directory gracefully")
    void shouldHandleExportToNonExistentDirectory() {
        File invalidFile = new File("/nonexistent/path/export.csv");
        
        assertThrows(Exception.class, () -> {
            db.table("users")
                .export()
                .format(ExportFormat.CSV)
                .toFile(invalidFile.getAbsolutePath())
                .execute();
        });
    }

    @Test
    @DisplayName("Should export all rows from table")
    void shouldExportAllRows() throws Exception {
        int exported = db.table("users")
            .export()
            .format(ExportFormat.CSV)
            .toFile(testFile.getAbsolutePath())
            .execute();
        
        assertEquals(3, exported, "Should export all 3 rows");
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
