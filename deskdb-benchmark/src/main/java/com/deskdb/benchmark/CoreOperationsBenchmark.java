package com.deskdb.benchmark;

import com.deskdb.core.DeskDB;
import com.deskdb.core.TableOperations;
import com.deskdb.core.Column;
import com.deskdb.core.DataType;
import com.deskdb.core.Transaction;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark for core database operations including insert, update, select, and delete.
 * Measures throughput and performance characteristics of DeskDB operations.
 */
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
    private static final int BATCH_SIZE = 100;

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
        
        // Pre-populate some data for select/update/delete operations
        populateTestData();
    }

    /**
     * Populates test data for benchmarks that require existing records.
     */
    private void populateTestData() throws Exception {
        try (Transaction tx = database.beginTransaction()) {
            for (int i = 1; i <= 1000; i++) {
                tx.table("users")
                    .insert()
                    .value("id", i)
                    .value("name", "User_" + i)
                    .value("email", "user" + i + "@example.com")
                    .value("age", 20 + (i % 50))
                    .value("balance", 100.0 + (i * 1.5))
                    .execute();
            }
            tx.commit();
        }
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

    /**
     * Benchmark for single insert operation with durability guarantees.
     * Measures the cost of writing a single record with WAL synchronization.
     */
    @Benchmark
    public void insertSingle_Durability(Blackhole bh) throws Exception {
        int id = counter.incrementAndGet();
        database.table("users")
            .insert()
            .value("id", id)
            .value("name", "User_" + id)
            .value("email", "user" + id + "@example.com")
            .value("age", 25)
            .value("balance", 100.0)
            .execute();
        bh.consume(true);
    }

    /**
     * Benchmark for batch insert operations within a transaction.
     * Measures throughput when inserting multiple records in a single transaction.
     */
    @Benchmark
    public void insertBatch_Throughput(Blackhole bh) throws Exception {
        int batchSize = BATCH_SIZE;
        int startId = counter.addAndGet(batchSize);
        
        try (Transaction tx = database.beginTransaction()) {
            for (int i = 0; i < batchSize; i++) {
                int id = startId + i;
                tx.table("users")
                    .insert()
                    .value("id", id)
                    .value("name", "User_" + id)
                    .value("email", "user" + id + "@example.com")
                    .value("age", 20 + (i % 50))
                    .value("balance", 100.0 + (i * 1.5))
                    .execute();
            }
            tx.commit();
        }
        bh.consume(batchSize);
    }

    /**
     * Benchmark for batch insert using bulk API (if available).
     * Measures performance of optimized bulk insert operations.
     */
    @Benchmark
    public void insertBulk_Optimized(Blackhole bh) throws Exception {
        int batchSize = BATCH_SIZE;
        int startId = counter.addAndGet(batchSize);
        
        List<Object[]> batchData = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            int id = startId + i;
            Object[] row = new Object[] {
                id,
                "User_" + id,
                "user" + id + "@example.com",
                20 + (i % 50),
                100.0 + (i * 1.5)
            };
            batchData.add(row);
        }
        
        try (Transaction tx = database.beginTransaction()) {
            TableOperations table = tx.table("users");
            for (Object[] row : batchData) {
                table.insert()
                    .value("id", row[0])
                    .value("name", row[1])
                    .value("email", row[2])
                    .value("age", row[3])
                    .value("balance", row[4])
                    .execute();
            }
            tx.commit();
        }
        bh.consume(batchSize);
    }

    /**
     * Benchmark for select by primary key operation.
     * Measures point query performance using indexed lookup.
     */
    @Benchmark
    public void selectByIdOperation(Blackhole bh) throws Exception {
        var results = tableOps.select().where("id").eq(50).execute();
        bh.consume(results);
    }

    /**
     * Benchmark for full table scan operation.
     * Measures performance of selecting all records from the table.
     */
    @Benchmark
    public void selectAllOperation(Blackhole bh) throws Exception {
        var results = tableOps.select().execute();
        bh.consume(results);
    }

    /**
     * Benchmark for update operation by primary key.
     * Measures the cost of updating a single record.
     */
    @Benchmark
    public void updateOperation(Blackhole bh) throws Exception {
        int updated = tableOps.update().set("name", "UpdatedUser").where("id").eq(50).execute();
        bh.consume(updated);
    }

    /**
     * Benchmark for delete operation by primary key.
     * Includes re-insertion to maintain consistent dataset size.
     */
    @Benchmark
    public void deleteOperation(Blackhole bh) throws Exception {
        int deleted = tableOps.delete().where("id").eq(99).execute();
        bh.consume(deleted);
        tableOps.insert()
            .value("id", 99)
            .value("name", "User99")
            .value("email", "user99@test.com")
            .value("age", 124)
            .value("balance", 100.0)
            .execute();
    }
}
