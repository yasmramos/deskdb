package com.deskdb.core;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AdvancedQueryTest {
    private Path tempDbPath;
    private DeskDB db;

    @BeforeEach
    void setUp() throws Exception {
        tempDbPath = Files.createTempFile("test", ".deskdb");
        db = DeskDB.open(tempDbPath);
        db.createTable("usuarios",
            new Column("id", DataType.LONG).primaryKey(),
            new Column("nombre", DataType.STRING),
            new Column("edad", DataType.INT),
            new Column("activo", DataType.BOOLEAN)
        );
        
        // Insertar datos de prueba
        insertUser(1L, "Ana", 30, true);
        insertUser(2L, "Luis", 25, false);
        insertUser(3L, "Carlos", 35, true);
        insertUser(4L, "Beatriz", 28, true);
        insertUser(5L, "David", 40, false);
    }
    
    private void insertUser(Long id, String nombre, Integer edad, Boolean activo) throws Exception {
        db.table("usuarios").insert()
            .value("id", id)
            .value("nombre", nombre)
            .value("edad", edad)
            .value("activo", activo)
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

    @Test
    void testOrderByAsc() throws Exception {
        List<Row> results = db.table("usuarios")
            .select()
            .orderBy("edad")
            .execute();

        assertEquals(5, results.size());
        assertEquals(25, results.get(0).get("edad"));
        assertEquals(40, results.get(4).get("edad"));
    }

    @Test
    void testOrderByDesc() throws Exception {
        List<Row> results = db.table("usuarios")
            .select()
            .orderByDesc("edad")
            .execute();

        assertEquals(5, results.size());
        assertEquals(40, results.get(0).get("edad"));
        assertEquals(25, results.get(4).get("edad"));
    }

    @Test
    void testLimit() throws Exception {
        List<Row> results = db.table("usuarios")
            .select()
            .limit(3)
            .execute();

        assertEquals(3, results.size());
    }

    @Test
    void testOffset() throws Exception {
        List<Row> allResults = db.table("usuarios").select().execute();
        List<Row> results = db.table("usuarios")
            .select()
            .offset(2)
            .execute();

        assertEquals(3, results.size());
        assertEquals(allResults.get(2).get("id"), results.get(0).get("id"));
    }

    @Test
    void testLimitAndOffset() throws Exception {
        List<Row> results = db.table("usuarios")
            .select()
            .offset(1)
            .limit(2)
            .execute();

        assertEquals(2, results.size());
    }

    @Test
    void testOrderByWithLimit() throws Exception {
        List<Row> results = db.table("usuarios")
            .select()
            .orderByDesc("edad")
            .limit(2)
            .execute();

        assertEquals(2, results.size());
        assertEquals(40, results.get(0).get("edad"));
        assertEquals(35, results.get(1).get("edad"));
    }

    @Test
    void testAndCondition() throws Exception {
        Filter f1 = new Filter("edad", Filter.Operator.GT, 25);
        Filter f2 = new Filter("activo", Filter.Operator.EQ, true);
        Filter andFilter = f1.and(f2);

        List<Row> results = db.table("usuarios")
            .select()
            .addFilter(andFilter)
            .execute();

        // Ana (30, activo), Carlos (35, activo), Beatriz (28, activo)
        assertEquals(3, results.size());
        for (Row row : results) {
            assertTrue((Integer) row.get("edad") > 25);
            assertTrue((Boolean) row.get("activo"));
        }
    }

    @Test
    void testOrCondition() throws Exception {
        Filter f1 = new Filter("edad", Filter.Operator.LT, 26);
        Filter f2 = new Filter("edad", Filter.Operator.GT, 38);
        Filter orFilter = f1.or(f2);

        List<Row> results = db.table("usuarios")
            .select()
            .addFilter(orFilter)
            .execute();

        // Luis (25), David (40)
        assertEquals(2, results.size());
    }

    @Test
    void testComplexAndOrConditions() throws Exception {
        // (edad > 30 AND activo = true) OR edad > 39
        Filter f1 = new Filter("edad", Filter.Operator.GT, 30);
        Filter f2 = new Filter("activo", Filter.Operator.EQ, true);
        Filter andFilter = f1.and(f2);
        Filter f3 = new Filter("edad", Filter.Operator.GT, 39);
        Filter complexFilter = andFilter.or(f3);

        List<Row> results = db.table("usuarios")
            .select()
            .addFilter(complexFilter)
            .execute();

        // Carlos (35, activo=true), David (40)
        assertEquals(2, results.size());
    }

    @Test
    void testOrderByWithStringColumn() throws Exception {
        List<Row> results = db.table("usuarios")
            .select()
            .orderBy("nombre")
            .execute();

        assertEquals(5, results.size());
        assertEquals("Ana", results.get(0).get("nombre"));
        assertEquals("Luis", results.get(4).get("nombre"));
    }

    @Test
    void testCombinedFeatures() throws Exception {
        // WHERE edad > 25 AND activo = true, ORDER BY edad DESC, LIMIT 2
        Filter f1 = new Filter("edad", Filter.Operator.GT, 25);
        Filter f2 = new Filter("activo", Filter.Operator.EQ, true);
        Filter andFilter = f1.and(f2);

        List<Row> results = db.table("usuarios")
            .select()
            .addFilter(andFilter)
            .orderByDesc("edad")
            .limit(2)
            .execute();

        assertEquals(2, results.size());
        // Carlos (35), Ana (30) o Beatriz (28) - depende del orden
        assertTrue((Integer) results.get(0).get("edad") >= (Integer) results.get(1).get("edad"));
    }
}
