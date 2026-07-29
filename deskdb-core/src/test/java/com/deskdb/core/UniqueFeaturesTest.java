package com.deskdb.core;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Unique Features Tests")
public class UniqueFeaturesTest {

    private static DeskDB db;
    private static Path testDir;

    @BeforeAll
    static void setUp() throws Exception {
        testDir = Files.createTempDirectory("deskdb-unique-features");
        db = new DeskDB(testDir);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
        if (testDir != null) {
            Files.walk(testDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        }
    }

    @Nested
    @DisplayName("Soft Delete Tests")
    class SoftDeleteTests {

        @BeforeEach
        void setup() {
            db.table("users").drop().execute();
            db.table("users")
                .create()
                .column("id", DataType.INTEGER).primaryKey()
                .column("name", DataType.STRING)
                .column("deleted", DataType.BOOLEAN)
                .column("deletedAt", DataType.TIMESTAMP)
                .execute();
        }

        @Test
        @DisplayName("Should soft delete single row")
        void testSoftDeleteSingle() {
            db.table("users").insert()
                .value("id", 1)
                .value("name", "John")
                .value("deleted", false)
                .value("deletedAt", null)
                .execute();

            db.table("users").delete().soft().where(u -> u.field("id").eq(1)).execute();

            List<Map<String, Object>> all = db.table("users").select().execute();
            List<Map<String, Object>> active = db.table("users").select().where(u -> u.field("deleted").eq(false)).execute();

            assertEquals(1, all.size(), "Row should exist but be marked deleted");
            assertEquals(0, active.size(), "No active rows should be returned");
        }

        @Test
        @DisplayName("Should restore soft deleted row")
        void testRestoreSingle() {
            db.table("users").insert()
                .value("id", 2)
                .value("name", "Jane")
                .value("deleted", true)
                .value("deletedAt", LocalDateTime.now())
                .execute();

            db.table("users").restore().where(u -> u.field("id").eq(2)).execute();

            List<Map<String, Object>> active = db.table("users").select().where(u -> u.field("deleted").eq(false)).execute();
            assertEquals(1, active.size());
            assertFalse((Boolean) active.get(0).get("deleted"));
        }

        @Test
        @DisplayName("Should soft delete multiple rows")
        void testSoftDeleteMultiple() {
            for (int i = 10; i <= 18; i++) {
                db.table("users").insert()
                    .value("id", i)
                    .value("name", "User" + i)
                    .value("deleted", false)
                    .value("deletedAt", null)
                    .execute();
            }

            db.table("users").delete().soft().where(u -> u.field("id").gt(15)).execute();

            List<Map<String, Object>> all = db.table("users").select().execute();
            List<Map<String, Object>> active = db.table("users").select().where(u -> u.field("deleted").eq(false)).execute();

            assertEquals(9, all.size());
            assertEquals(6, active.size());
        }
    }

    @Nested
    @DisplayName("Export/Import Tests")
    class ExportImportTests {

        @BeforeEach
        void setup() {
            db.table("products").drop().execute();
            db.table("products")
                .create()
                .column("id", DataType.INTEGER).primaryKey()
                .column("name", DataType.STRING)
                .column("price", DataType.DOUBLE)
                .execute();
        }

        @Test
        @DisplayName("Should verify ImportFormat enum values")
        void testImportFormatEnum() {
            ImportFormat[] formats = ImportFormat.values();
            assertEquals(4, formats.length, "Should have 4 import formats");
            assertTrue(java.util.Arrays.asList(formats).contains(ImportFormat.CSV));
            assertTrue(java.util.Arrays.asList(formats).contains(ImportFormat.JSON));
            assertTrue(java.util.Arrays.asList(formats).contains(ImportFormat.XML));
            assertTrue(java.util.Arrays.asList(formats).contains(ImportFormat.PARQUET));
        }

        @Test
        @DisplayName("Should export to CSV")
        void testExportCSV() throws Exception {
            db.table("products").insert()
                .value("id", 100)
                .value("name", "Laptop")
                .value("price", 999.99)
                .execute();

            File outFile = testDir.resolve("products.csv").toFile();
            db.table("products").export().format(ExportFormat.CSV).toFile(outFile.getAbsolutePath()).execute();

            assertTrue(outFile.exists());
            String content = Files.readString(outFile.toPath());
            assertTrue(content.contains("Laptop"));
        }

        @Test
        @DisplayName("Should import from JSON")
        void testImportJSON() throws Exception {
            File inFile = testDir.resolve("products.json").toFile();
            String json = "[{\"id\":200,\"name\":\"Phone\",\"price\":499.99}]";
            Files.writeString(inFile.toPath(), json);

            db.table("products").importData().format(ImportFormat.JSON).fromFile(inFile.getAbsolutePath()).execute();

            List<Map<String, Object>> result = db.table("products").select().where(u -> u.field("id").eq(200)).execute();
            assertEquals(1, result.size());
            assertEquals("Phone", result.get(0).get("name"));
        }
    }

    @Nested
    @DisplayName("Time Travel Tests")
    class TimeTravelTests {

        @BeforeEach
        void setup() {
            db.table("accounts").drop().execute();
            db.table("accounts")
                .create()
                .column("id", DataType.INTEGER).primaryKey()
                .column("balance", DataType.DOUBLE)
                .column("version", DataType.INTEGER)
                .execute();
        }

        @Test
        @DisplayName("Should track versions on update")
        void testVersionTracking() {
            db.table("accounts").insert()
                .value("id", 1)
                .value("balance", 100.0)
                .value("version", 1)
                .execute();

            db.table("accounts").update()
                .value("balance", 200.0)
                .value("version", 2)
                .where(u -> u.field("id").eq(1))
                .execute();

            List<Map<String, Object>> history = db.table("accounts").history(1L).execute();
            assertTrue(history.size() >= 1, "Should have at least one version in history");
        }

        @Test
        @DisplayName("Should query history as of timestamp")
        void testHistoryAsOf() {
            LocalDateTime before = LocalDateTime.now();
            
            db.table("accounts").insert()
                .value("id", 2)
                .value("balance", 50.0)
                .value("version", 1)
                .execute();

            try { Thread.sleep(10); } catch (InterruptedException e) {}

            db.table("accounts").update()
                .value("balance", 75.0)
                .value("version", 2)
                .where(u -> u.field("id").eq(2))
                .execute();

            List<Map<String, Object>> history = db.table("accounts").history(2L).asOf(before).execute();
            // Just verify it doesn't crash and returns list
            assertNotNull(history);
        }
    }
}
