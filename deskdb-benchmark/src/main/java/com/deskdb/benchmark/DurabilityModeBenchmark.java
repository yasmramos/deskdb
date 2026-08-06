package com.deskdb.benchmark;

import com.deskdb.core.Column;
import com.deskdb.core.DataType;
import com.deskdb.core.DeskDB;
import com.deskdb.core.Transaction;
import com.deskdb.storage.Wal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark para comparar los diferentes modos de durabilidad del WAL.
 * 
 * Este benchmark demuestra cómo el modo ASYNC_COMMIT puede mejorar drásticamente
 * el rendimiento de escrituras en lote a costa de durabilidad.
 */
public class DurabilityModeBenchmark {
    
    private static final int BATCH_SIZE = 100;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURE_ITERATIONS = 10;
    
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("DESKDB - BENCHMARK DE MODOS DE DURABILIDAD");
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Probar cada modo de durabilidad
        testDurabilityMode(Wal.DurabilityMode.FULL_SYNC, "FULL_SYNC (Máxima Seguridad)");
        testDurabilityMode(Wal.DurabilityMode.GROUP_COMMIT, "GROUP_COMMIT (Balance)");
        testDurabilityMode(Wal.DurabilityMode.ASYNC_COMMIT, "ASYNC_COMMIT (Máximo Rendimiento)");
        
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("RESUMEN:");
        System.out.println("- FULL_SYNC:     Máxima seguridad, menor rendimiento (fsync en cada commit)");
        System.out.println("- GROUP_COMMIT:  Balance entre seguridad y rendimiento");
        System.out.println("- ASYNC_COMMIT:  Máximo rendimiento, riesgo de pérdida de datos");
        System.out.println("=".repeat(80));
    }
    
    private static void testDurabilityMode(Wal.DurabilityMode mode, String modeName) throws Exception {
        System.out.println();
        System.out.println("-".repeat(80));
        System.out.println("Probando modo: " + modeName);
        System.out.println("-".repeat(80));
        
        Path dbPath = Files.createTempDirectory("deskdb-benchmark-");
        
        try {
            // Warmup
            System.out.println("Warmup...");
            runBenchmark(dbPath.resolve("warmup"), mode, WARMUP_ITERATIONS);
            
            // Medición
            System.out.println("Medición...");
            long totalOpsPerSec = runBenchmark(dbPath.resolve("measure"), mode, MEASURE_ITERATIONS);
            
            System.out.println();
            System.out.println(">>> RESULTADO: " + String.format("%,d", totalOpsPerSec) + " ops/segundo");
            
        } finally {
            // Cleanup
            deleteRecursively(dbPath.toFile());
        }
    }
    
    private static long runBenchmark(Path path, Wal.DurabilityMode mode, int iterations) throws Exception {
        long totalTime = 0;
        long totalOps = 0;
        
        for (int i = 0; i < iterations; i++) {
            Path dbPath = path.resolve("iter_" + i);
            
            try (DeskDB database = DeskDB.open(dbPath.toString())) {
                // Configurar modo de durabilidad
                database.setDurabilityMode(mode);
                
                // Crear tabla
                database.createTable("users",
                    new Column("id", DataType.INT).primaryKey(),
                    new Column("name", DataType.STRING),
                    new Column("email", DataType.STRING),
                    new Column("age", DataType.INT),
                    new Column("balance", DataType.DOUBLE)
                );
                
                // Medir tiempo de inserción en lote
                AtomicInteger counter = new AtomicInteger(0);
                int batchSize = BATCH_SIZE;
                
                long startTime = System.nanoTime();
                
                try (Transaction tx = database.beginTransaction()) {
                    for (int j = 0; j < batchSize; j++) {
                        int id = counter.addAndGet(1);
                        tx.table("users")
                            .insert()
                            .value("id", id)
                            .value("name", "User_" + id)
                            .value("email", "user" + id + "@example.com")
                            .value("age", 20 + (j % 50))
                            .value("balance", 100.0 + (j * 1.5))
                            .execute();
                    }
                    tx.commit();
                }
                
                long endTime = System.nanoTime();
                long durationMs = (endTime - startTime) / 1_000_000;
                
                if (durationMs > 0) {
                    long opsPerSec = (batchSize * 1_000) / durationMs;
                    totalTime += durationMs;
                    totalOps += batchSize;
                    
                    System.out.printf("  Iteración %d: %d registros en %d ms (%,d ops/s)%n", 
                                    i + 1, batchSize, durationMs, opsPerSec);
                }
            }
        }
        
        return totalTime > 0 ? (totalOps * 1_000) / totalTime : 0;
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
