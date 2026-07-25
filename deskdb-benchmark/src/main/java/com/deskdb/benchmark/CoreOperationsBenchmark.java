package com.deskdb.benchmark;

import com.deskdb.core.DeskDB;
import com.deskdb.core.TableOperations;
import com.deskdb.core.Column;
import com.deskdb.core.DataType;
import com.deskdb.core.Transaction;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class CoreOperationsBenchmark {

    private DeskDB database;
    private TableOperations tableOps;
    private AtomicInteger counter = new AtomicInteger(1000);

    @Setup
    public void setup() throws Exception {
        String tempDbName = "bench_db_" + System.nanoTime() + ".deskdb";
        database = DeskDB.open(tempDbName);
        database.createTable("users",
            new Column("id", DataType.INT).primaryKey(),
            new Column("name", DataType.STRING),
            new Column("email", DataType.STRING),
            new Column("age", DataType.INT),
            new Column("balance", DataType.DOUBLE)
        );
        tableOps = database.table("users");
    }

    @TearDown
    public void tearDown() throws Exception {
        if (database != null) {
            database.close();
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(database.getFilePath()));
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(database.getFilePath() + ".wal"));
            } catch (Exception e) { /* ignore */ }
        }
    }

    @Benchmark
    public void insertSingle_Durability(Blackhole bh) throws Exception {
        try (Transaction tx = new Transaction(database, false)) {
            database.table("users")
                .insert()
                .value("id", counter.incrementAndGet())
                .value("name", "User")
                .value("email", "u@e.com")
                .value("age", 25)
                .value("balance", 100.0)
                .execute(tx);
            tx.commit();
        }
        bh.consume(true);
    }

    @Benchmark
    public void insertBatch_Throughput(Blackhole bh) throws Exception {
        try (Transaction tx = new Transaction(database, false)) {
            for (int i = 0; i < 1000; i++) {
                database.table("users")
                    .insert()
                    .value("id", counter.incrementAndGet())
                    .value("name", "User")
                    .value("email", "u@e.com")
                    .value("age", 25)
                    .value("balance", 100.0)
                    .execute(tx);
            }
            tx.commit();
        }
        bh.consume(true);
    }

    @Benchmark
    public void selectByIdOperation(Blackhole bh) throws Exception {
        var results = tableOps.select().where("id").eq(50).execute();
        bh.consume(results);
    }

    @Benchmark
    public void selectAllOperation(Blackhole bh) throws Exception {
        var results = tableOps.select().execute();
        bh.consume(results);
    }

    @Benchmark
    public void updateOperation(Blackhole bh) throws Exception {
        int updated = tableOps.update().set("name", "UpdatedUser").where("id").eq(50).execute();
        bh.consume(updated);
    }

    @Benchmark
    public void deleteOperation(Blackhole bh) throws Exception {
        int deleted = tableOps.delete().where("id").eq(99).execute();
        bh.consume(deleted);
        tableOps.insert().value("id", 99).value("name", "User99").value("email", "user99@test.com").value("age", 124).execute();
    }
}
