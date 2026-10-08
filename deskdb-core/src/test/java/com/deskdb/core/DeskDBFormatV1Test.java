package com.deskdb.core;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests del formato en disco .deskdb v1:
 * - Cabecera magic + versión
 * - Códigos de tipo explícitos (no ordinal)
 * - Round-trip de Timestamp con nanosegundos (bug #1)
 * - Apertura fallida (sin degradación silenciosa) ante archivo corrupto
 * - Persistencia del esquema de BD nuevas sin filas (bug #5)
 */
public class DeskDBFormatV1Test {
    private Path tempDbPath;

    @BeforeEach
    void setUp() throws IOException {
        tempDbPath = Files.createTempFile("fmt-v1-test", ".deskdb");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDbPath != null && Files.exists(tempDbPath)) {
            Files.deleteIfExists(tempDbPath);
        }
        Path walPath = Paths.get(tempDbPath.toString() + ".wal");
        Files.deleteIfExists(walPath);
    }

    private void cleanupWal() throws IOException {
        Files.deleteIfExists(Paths.get(tempDbPath.toString() + ".wal"));
    }

    @Test
    void savedFileStartsWithMagicAndVersionHeader() throws Exception {
        try (DeskDB db = DeskDB.open(tempDbPath)) {
            db.createTable("t", new Column("id", DataType.LONG).primaryKey());
            db.saveToFile();
        }
        byte[] content = Files.readAllBytes(tempDbPath);
        assertTrue(content.length > 6, "archivo no vacío");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(content));
        assertEquals(DeskDB.MAGIC, in.readInt(), "magic 'DESK'");
        assertEquals(DeskDB.FORMAT_VERSION, in.readShort(), "versión 1");
        cleanupWal();
    }

    @Test
    void timestampRoundTripPreservesNanos() throws Exception {
        java.sql.Timestamp original = java.sql.Timestamp.from(
                java.time.LocalDateTime.of(2026, 10, 8, 12, 30, 45, 123456789)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant());

        try (DeskDB db = DeskDB.open(tempDbPath)) {
            db.createTable("eventos",
                    new Column("id", DataType.LONG).primaryKey(),
                    new Column("ts", DataType.TIMESTAMP));
            long id = db.getTable("eventos").getNextRowId();
            db.getTable("eventos").getData().put(id,
                    new Row(id, java.util.Map.of("id", id, "ts", original)));
            db.saveToFile();
        }
        cleanupWal();

        try (DeskDB db2 = DeskDB.open(tempDbPath)) {
            Table t = db2.getTable("eventos");
            Row row = t.getData().values().iterator().next();
            Object value = row.get("ts");
            assertInstanceOf(java.sql.Timestamp.class, value,
                    "debe deserializarse como Timestamp, no como Date");
            java.sql.Timestamp loaded = (java.sql.Timestamp) value;
            assertEquals(original.getNanos(), loaded.getNanos(),
                    "los nanosegundos deben sobrevivir al round-trip (bug writeValue Date/Timestamp)");
            assertEquals(original.getTime(), loaded.getTime());
        }
        cleanupWal();
    }

    @Test
    void allTypesRoundTripWithExplicitCodes() throws Exception {
        try (DeskDB db = DeskDB.open(tempDbPath)) {
            db.createTable("todo",
                    new Column("id", DataType.LONG).primaryKey(),
                    new Column("s", DataType.STRING),
                    new Column("i", DataType.INT),
                    new Column("l", DataType.LONG),
                    new Column("d", DataType.DOUBLE),
                    new Column("dec", DataType.DECIMAL),
                    new Column("b", DataType.BOOLEAN),
                    new Column("dt", DataType.DATE),
                    new Column("blob", DataType.BLOB));
            long id = 1L;
            java.util.Map<String, Object> values = new java.util.HashMap<>();
            values.put("id", id);
            values.put("s", "hola");
            values.put("i", 42);
            values.put("l", 9999999999L);
            values.put("d", 3.14159);
            values.put("dec", new BigDecimal("123.4567890123"));
            values.put("b", true);
            values.put("dt", new java.util.Date(1700000000000L));
            values.put("blob", new byte[]{1, 2, 3, 4, 5});
            db.getTable("todo").getData().put(id, new Row(id, values));
            db.saveToFile();
        }
        cleanupWal();

        try (DeskDB db2 = DeskDB.open(tempDbPath)) {
            Row row = db2.getTable("todo").getData().values().iterator().next();
            assertEquals("hola", row.get("s"));
            assertEquals(42, row.get("i"));
            assertEquals(9999999999L, row.get("l"));
            assertEquals(3.14159, row.get("d"));
            assertEquals(new BigDecimal("123.4567890123"), row.get("dec"));
            assertEquals(true, row.get("b"));
            assertEquals(new java.util.Date(1700000000000L), row.get("dt"));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, (byte[]) row.get("blob"));
        }
        cleanupWal();
    }

    @Test
    void corruptedFileRefusesToOpenInsteadOfSilentEmptyDb() throws Exception {
        // Archivo con bytes que no son un .deskdb válido
        Files.write(tempDbPath, "esto no es una base de datos".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> DeskDB.open(tempDbPath),
                "un archivo corrupto DEBE impedir abrir la BD (nunca arrancar vacía y sobrescribir)");
    }

    @Test
    void wrongVersionHeaderIsRejected() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(DeskDB.MAGIC);
        out.writeShort((short) 99); // versión futura no soportada
        out.flush();
        Files.write(tempDbPath, baos.toByteArray());

        assertThrows(IOException.class, () -> DeskDB.open(tempDbPath),
                "una versión de formato desconocida debe rechazarse explícitamente");
    }

    @Test
    void newDatabaseSchemaPersistsWithoutRows() throws Exception {
        // Bug #5: BD nueva con tabla creada y cero filas debía persistir su esquema al close()
        Path fresh = Files.createTempDirectory("fresh-db");
        Path dbFile = fresh.resolve("nueva.deskdb");
        try (DeskDB db = DeskDB.open(dbFile)) {
            db.createTable("config",
                    new Column("clave", DataType.STRING).primaryKey(),
                    new Column("valor", DataType.STRING));
            // Sin insertar ninguna fila
        }
        assertTrue(Files.isRegularFile(dbFile), "el archivo debe crearse al cerrar aunque no haya filas");

        try (DeskDB db2 = DeskDB.open(dbFile)) {
            Table t = db2.getTable("config"); // lanza si el esquema no se persistió
            assertNotNull(t);
            assertEquals(2, db2.getSchema("config").getColumnsList().size());
        }
        // limpieza
        Files.deleteIfExists(Paths.get(dbFile.toString() + ".wal"));
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(fresh);
    }

    @Test
    void saveLoadSaveIsByteDeterministic() throws Exception {
        try (DeskDB db = DeskDB.open(tempDbPath)) {
            db.createTable("ord", new Column("id", DataType.LONG).primaryKey(),
                    new Column("n", DataType.INT));
            for (long i = 1; i <= 20; i++) {
                db.getTable("ord").getData().put(i,
                        new Row(i, java.util.Map.of("id", i, "n", (int) (i * 10))));
            }
            db.saveToFile();
        }
        cleanupWal();
        byte[] first = Files.readAllBytes(tempDbPath);

        // Reabrir y volver a guardar debe producir bytes idénticos (orden por rowId)
        try (DeskDB db2 = DeskDB.open(tempDbPath)) {
            db2.saveToFile();
        }
        cleanupWal();
        byte[] second = Files.readAllBytes(tempDbPath);
        assertArrayEquals(first, second, "save→load→save debe ser determinista byte a byte");
    }
}
