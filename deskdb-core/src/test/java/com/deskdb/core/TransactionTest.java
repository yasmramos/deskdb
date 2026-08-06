package com.deskdb.core;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionTest {

    private Path dbPath;

    @BeforeEach
    public void setUp() throws IOException {
        dbPath = Files.createTempFile("deskdb_tx_", ".deskdb");
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(Paths.get(dbPath.toString() + ".wal"));
    }

    @Test
    public void testCommit() throws Exception {
        try (DeskDB db = DeskDB.open(dbPath)) {
            db.createTable("usuarios",
                new Column("id", DataType.LONG).primaryKey(),
                new Column("nombre", DataType.STRING),
                new Column("edad", DataType.INT)
            );

            try (Transaction tx = db.beginTransaction()) {
                tx.table("usuarios")
                    .insert()
                    .value("id", 1L)
                    .value("nombre", "Ana")
                    .value("edad", 30)
                    .execute();
                tx.commit();
            }

            var results = db.table("usuarios").select().execute();
            assertEquals(1, results.size());
        }
    }

    @Test
    public void testRollback() throws Exception {
        try (DeskDB db = DeskDB.open(dbPath)) {
            db.createTable("usuarios",
                new Column("id", DataType.LONG).primaryKey(),
                new Column("nombre", DataType.STRING),
                new Column("edad", DataType.INT)
            );

            try (Transaction tx = db.beginTransaction()) {
                tx.table("usuarios")
                    .insert()
                    .value("id", 1L)
                    .value("nombre", "Ana")
                    .value("edad", 30)
                    .execute();
                tx.rollback();
            }

            var results = db.table("usuarios").select().execute();
            assertEquals(0, results.size());
        }
    }

    @Test
    public void testAutoRollbackOnException() throws Exception {
        try (DeskDB db = DeskDB.open(dbPath)) {
            db.createTable("usuarios",
                new Column("id", DataType.LONG).primaryKey(),
                new Column("nombre", DataType.STRING),
                new Column("edad", DataType.INT)
            );

            try {
                try (Transaction tx = db.beginTransaction()) {
                    tx.table("usuarios")
                        .insert()
                        .value("id", 1L)
                        .value("nombre", "Ana")
                        .value("edad", 30)
                        .execute();
                    throw new RuntimeException("Error forzado");
                }
            } catch (RuntimeException e) {
                // Esperado
            }

            var results = db.table("usuarios").select().execute();
            assertEquals(0, results.size());
        }
    }

    @Test
    public void testMultipleOperationsInTransaction() throws Exception {
        try (DeskDB db = DeskDB.open(dbPath)) {
            db.createTable("productos",
                new Column("id", DataType.LONG).primaryKey(),
                new Column("nombre", DataType.STRING),
                new Column("precio", DataType.DOUBLE)
            );

            try (Transaction tx = db.beginTransaction()) {
                tx.table("productos").insert()
                    .value("id", 1L).value("nombre", "Producto1").value("precio", 100.0)
                    .execute();
                tx.table("productos").insert()
                    .value("id", 2L).value("nombre", "Producto2").value("precio", 200.0)
                    .execute();
                tx.table("productos").insert()
                    .value("id", 3L).value("nombre", "Producto3").value("precio", 300.0)
                    .execute();
                tx.commit();
            }

            var results = db.table("productos").select().execute();
            assertEquals(3, results.size());
        }
    }

    @Test
    public void testIsolation() throws Exception {
        try (DeskDB db = DeskDB.open(dbPath)) {
            db.createTable("test",
                new Column("id", DataType.LONG).primaryKey(),
                new Column("valor", DataType.INT)
            );

            Transaction tx1 = db.beginTransaction();
            Transaction tx2 = db.beginTransaction();

            tx1.table("test").insert()
                .value("id", 1L).value("valor", 100)
                .execute();

            // tx2 no debería ver los cambios de tx1 sin commit
            var resultsBeforeCommit = tx2.table("test").select().execute();
            assertEquals(0, resultsBeforeCommit.size());

            tx1.commit();
            tx2.close();

            // Después del commit, tx2 (ya cerrado) no ve cambios, pero una nueva transacción sí
            try (Transaction tx3 = db.beginTransaction()) {
                var resultsAfterCommit = tx3.table("test").select().execute();
                assertEquals(1, resultsAfterCommit.size());
            }
        }
    }
}
