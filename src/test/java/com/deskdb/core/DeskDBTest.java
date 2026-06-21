package com.deskdb.core;
import java.nio.file.Paths;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DeskDBTest {
    private Path tempDbPath;
    private DeskDB db;

    @BeforeEach
    void setUp() throws IOException {
        tempDbPath = Files.createTempFile("test", ".deskdb");
        db = DeskDB.open(tempDbPath);
        // Crear tabla por defecto para las pruebas
        db.createTable("usuarios",
            new Column("id", DataType.LONG).primaryKey(),
            new Column("nombre", DataType.STRING),
            new Column("edad", DataType.INT)
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (tempDbPath != null && Files.exists(tempDbPath)) {
            Files.delete(tempDbPath);
        }
        // Limpiar WAL si existe
        Path walPath = Paths.get(tempDbPath.toString() + ".wal");
        if (Files.exists(walPath)) {
            Files.delete(walPath);
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
    void testInsertAndSelect() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        List<Row> results = db.table("usuarios").select().execute();

        assertEquals(1, results.size());
        Row row = results.get(0);
        assertEquals("Ana", row.get("nombre"));
        assertEquals(30, row.get("edad"));
    }

    @Test
    void testInsertMultipleAndSelectWithFilter() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.table("usuarios")
          .insert()
          .value("id", 2L)
          .value("nombre", "Luis")
          .value("edad", 25)
          .execute();

        List<Row> results = db.table("usuarios")
          .select()
          .where("edad")
          .greaterThan(26)
          .execute();

        assertEquals(1, results.size());
        assertEquals("Ana", results.get(0).get("nombre"));
    }

    @Test
    void testUpdate() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.table("usuarios")
          .update()
          .set("edad", 31)
          .where("id")
          .is(1L)
          .execute();

        List<Row> results = db.table("usuarios")
          .select()
          .where("id")
          .is(1L)
          .execute();

        assertEquals(1, results.size());
        assertEquals(31, results.get(0).get("edad"));
    }

    @Test
    void testDelete() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.table("usuarios")
          .delete()
          .where("id")
          .is(1L)
          .execute();

        List<Row> results = db.table("usuarios").select().execute();
        assertEquals(0, results.size());
    }

    @Test
    void testSelectColumns() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .value("email", "ana@example.com")
          .execute();

        List<Row> results = db.table("usuarios")
            .select()
            .columns("nombre", "edad")
            .execute();

        assertEquals(1, results.size());
        Row row = results.get(0);
        assertEquals(2, row.getColumns().size());
        assertEquals("Ana", row.get("nombre"));
        assertEquals(30, row.get("edad"));
        assertFalse(row.getColumns().contains("email"));
    }

    @Test
    void testEmptyTable() throws Exception {
        List<Row> results = db.table("usuarios").select().execute();
        assertEquals(0, results.size());
    }

    @Test
    void testPersistence() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.close();

        // Reabrir la misma base de datos
        db = DeskDB.open(tempDbPath);

        List<Row> results = db.table("usuarios").select().execute();
        assertEquals(1, results.size());
        assertEquals("Ana", results.get(0).get("nombre"));
    }

    @Test
    void testChainedWhereConditions() throws Exception {
        db.table("usuarios")
          .insert()
          .value("id", 1L)
          .value("nombre", "Ana")
          .value("edad", 30)
          .execute();

        db.table("usuarios")
          .insert()
          .value("id", 2L)
          .value("nombre", "Luis")
          .value("edad", 25)
          .execute();

        db.table("usuarios")
          .insert()
          .value("id", 3L)
          .value("nombre", "Carlos")
          .value("edad", 35)
          .execute();

        // Buscar usuarios con edad entre 26 y 34
        List<Row> results = db.table("usuarios")
          .select()
          .where("edad")
          .greaterThan(26)
          .where("edad")
          .lessThan(34)
          .execute();

        assertEquals(1, results.size());
        assertEquals("Ana", results.get(0).get("nombre"));
    }
}
