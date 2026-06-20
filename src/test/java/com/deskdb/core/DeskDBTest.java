package com.deskdb.core;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias para DeskDB.
 */
public class DeskDBTest {
    private Path tempDbPath;
    private DeskDB db;

    @BeforeEach
    void setUp() throws IOException {
        tempDbPath = Files.createTempFile("test", ".deskdb");
        db = DeskDB.open(tempDbPath);
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

    @Test
    void testOpenAndClose() throws IOException {
        assertNotNull(db);
        assertFalse(db.isClosed());

        db.close();
        assertTrue(db.isClosed());
    }

    @Test
    void testInsertAndSelect() {
        db.table("usuarios")
          .insert()
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        List<Map<String, Object>> results = db.table("usuarios").select().execute();

        assertEquals(1, results.size());
        Map<String, Object> row = results.get(0);
        assertEquals("Ana", row.get("nombre"));
        assertEquals(30, row.get("edad"));
    }

    @Test
    void testInsertMultipleAndSelectWithFilter() {
        db.table("usuarios")
          .insert()
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.table("usuarios")
          .insert()
          .value("nombre", "Carlos")
          .value("edad", 25)
          .execute();

        db.table("usuarios")
          .insert()
          .value("nombre", "Beatriz")
          .value("edad", 35)
          .execute();

        // Filtrar por edad > 28
        List<Map<String, Object>> results = db.table("usuarios")
            .select()
            .where("edad")
            .greaterThan(28)
            .execute();

        assertEquals(2, results.size());
    }

    @Test
    void testUpdate() {
        db.table("usuarios")
          .insert()
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        var updatedResult = db.table("usuarios")
            .update()
            .set("edad", 31)
            .where("nombre")
            .equalTo("Ana")
            .execute();

        assertEquals(1, ((Number)updatedResult.get(0).get("_updated")).intValue());

        List<Map<String, Object>> results = db.table("usuarios")
            .select()
            .where("nombre")
            .equalTo("Ana")
            .execute();

        assertEquals(1, results.size());
        assertEquals(31, results.get(0).get("edad"));
    }

    @Test
    void testDelete() {
        db.table("usuarios")
          .insert()
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.table("usuarios")
          .insert()
          .value("nombre", "Carlos")
          .value("edad", 25)
          .execute();

        var deletedResult = db.table("usuarios")
            .delete()
            .where("nombre")
            .equalTo("Ana")
            .execute();

        assertEquals(1, ((Number)deletedResult.get(0).get("_deleted")).intValue());

        List<Map<String, Object>> results = db.table("usuarios").select().execute();
        assertEquals(1, results.size());
        assertEquals("Carlos", results.get(0).get("nombre"));
    }

    @Test
    void testSelectColumns() {
        db.table("usuarios")
          .insert()
          .value("nombre", "Ana")
          .value("edad", 30)
          .value("email", "ana@example.com")
          .execute();

        List<Map<String, Object>> results = db.table("usuarios")
            .select()
            .columns("nombre", "edad")
            .execute();

        assertEquals(1, results.size());
        Map<String, Object> row = results.get(0);
        assertEquals(2, row.size());
        assertEquals("Ana", row.get("nombre"));
        assertEquals(30, row.get("edad"));
        assertFalse(row.containsKey("email"));
    }

    @Test
    void testPersistence() throws IOException {
        db.table("usuarios")
          .insert()
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.close();

        // Reabrir la misma base de datos
        db = DeskDB.open(tempDbPath);

        List<Map<String, Object>> results = db.table("usuarios").select().execute();

        assertEquals(1, results.size());
        assertEquals("Ana", results.get(0).get("nombre"));
        assertEquals(30, results.get(0).get("edad"));
    }

    @Test
    void testEmptyTable() {
        List<Map<String, Object>> results = db.table("usuarios").select().execute();
        assertTrue(results.isEmpty());
    }

    @Test
    void testChainedWhereConditions() {
        db.table("productos")
          .insert()
          .value("nombre", "Laptop")
          .value("precio", 1000)
          .value("stock", 5)
          .execute();

        db.table("productos")
          .insert()
          .value("nombre", "Mouse")
          .value("precio", 25)
          .value("stock", 50)
          .execute();

        db.table("productos")
          .insert()
          .value("nombre", "Teclado")
          .value("precio", 75)
          .value("stock", 30)
          .execute();

        // Productos con precio > 50
        List<Map<String, Object>> results = db.table("productos")
            .select()
            .where("precio")
            .greaterThan(50)
            .execute();

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> "Laptop".equals(r.get("nombre")))); assertTrue(results.stream().anyMatch(r -> "Teclado".equals(r.get("nombre"))));
    }
}
