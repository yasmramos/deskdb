package com.deskdb.benchmark;

import com.deskdb.core.DeskDB;
import com.deskdb.core.TableOperations;
import com.deskdb.core.Column;
import com.deskdb.core.DataType;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark de operaciones CRUD básicas en DeskDB.
 * Mide throughput y latencia de inserciones, lecturas, actualizaciones y eliminaciones.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
public class CoreOperationsBenchmark {

    private DeskDB database;
    private TableOperations tableOps;

    @Setup
    public void setup() throws Exception {
        String tempDbName = "bench_db_" + System.nanoTime() + ".deskdb";
        database = DeskDB.open(tempDbName);
        database.createTable("users",
            new Column("id", DataType.INT).primaryKey(),
            new Column("name", DataType.STRING),
            new Column("email", DataType.STRING),
            new Column("age", DataType.INT)
        );
        
        tableOps = database.table("users");
        
        // Precargar algunos datos para tests de lectura/actualización
        for (int i = 0; i < 100; i++) {
            tableOps.insert()
                    .value("id", i)
                    .value("name", "User" + i)
                    .value("email", "user" + i + "@test.com")
                    .value("age", 20 + i)
                    .execute();
        }
    }

    @TearDown
    public void tearDown() throws Exception {
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                // Ignorar errores en cleanup durante benchmark
            }
            // Eliminar archivo temporal
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(database.getFilePath()));
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(database.getFilePath() + ".wal"));
            } catch (Exception e) {
                // Ignorar errores al eliminar archivos
            }
        }
    }

    @Benchmark
    public void insertOperation(Blackhole bh) throws Exception {
        int id = (int) System.nanoTime();
        tableOps.insert()
                .value("id", id)
                .value("name", "User" + id)
                .value("email", "user" + id + "@test.com")
                .value("age", 25)
                .execute();
        bh.consume(true);
    }

    @Benchmark
    public void selectByIdOperation(Blackhole bh) throws Exception {
        var results = tableOps.select()
                .where("id").eq(50)
                .execute();
        bh.consume(results);
    }

    @Benchmark
    public void selectAllOperation(Blackhole bh) throws Exception {
        var results = tableOps.select().execute();
        bh.consume(results);
    }

    @Benchmark
    public void updateOperation(Blackhole bh) throws Exception {
        int updated = tableOps.update()
                .set("name", "UpdatedUser")
                .where("id").eq(50)
                .execute();
        bh.consume(updated);
    }

    @Benchmark
    public void deleteOperation(Blackhole bh) throws Exception {
        int deleted = tableOps.delete()
                .where("id").eq(99)
                .execute();
        bh.consume(deleted);
        
        // Re-insertar para mantener estado consistente
        tableOps.insert()
                .value("id", 99)
                .value("name", "User99")
                .value("email", "user99@test.com")
                .value("age", 124)
                .execute();
    }
}
