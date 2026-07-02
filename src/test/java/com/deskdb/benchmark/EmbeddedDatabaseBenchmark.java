package com.deskdb.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.deskdb.core.DeskDB;
import com.deskdb.core.Transaction;
import com.deskdb.core.Row;

/**
 * Benchmark comparing DeskDB against popular embedded Java databases:
 * - H2 Database
 * - SQLite (via JDBC)
 * - HSQLDB
 * 
 * Tests include:
 * - Insert performance (single and batch)
 * - Read performance (point queries and range scans)
 * - Update performance
 * - Delete performance
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class EmbeddedDatabaseBenchmark {

    // Test data size
    private static final int BATCH_SIZE = 1000;
    
    // Database instances
    private DeskDB deskDB;
    private Connection h2Conn;
    private Connection sqliteConn;
    private Connection hsqldbConn;
    
    // File paths for cleanup
    private File deskDBFile;
    private File h2File;
    private File sqliteFile;
    private File hsqldbFile;
    
    @Setup(Level.Trial)
    public void setupTrial() throws SQLException {
        // Create temporary files
        String tempDir = System.getProperty("java.io.tmpdir");
        
        // DeskDB setup
        deskDBFile = new File(tempDir, "bench_desk_" + System.nanoTime() + ".deskdb");
        try {
            deskDB = DeskDB.open(deskDBFile.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        // H2 setup
        h2File = new File(tempDir, "bench_h2_" + System.nanoTime());
        h2Conn = DriverManager.getConnection("jdbc:h2:" + h2File.getAbsolutePath());
        setupH2Schema();
        
        // SQLite setup
        sqliteFile = new File(tempDir, "bench_sqlite_" + System.nanoTime() + ".db");
        sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
        setupSQLiteSchema();
        
        // HSQLDB setup
        hsqldbFile = new File(tempDir, "bench_hsqldb_" + System.nanoTime());
        hsqldbConn = DriverManager.getConnection("jdbc:hsqldb:file:" + hsqldbFile.getAbsolutePath());
        setupHSQLDBSchema();
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() throws SQLException {
        // Close all connections
        if (deskDB != null) {
            try {
                deskDB.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (h2Conn != null) h2Conn.close();
        if (sqliteConn != null) sqliteConn.close();
        if (hsqldbConn != null) hsqldbConn.close();
        
        // Cleanup files
        cleanupFiles();
    }
    
    @Setup(Level.Iteration)
    public void setupIteration() throws SQLException {
        // Clear data before each iteration
        clearDeskDB();
        clearH2();
        clearSQLite();
        clearHSQLDB();
    }
    
    private void cleanupFiles() {
        // Delete DeskDB files
        if (deskDBFile != null) {
            deskDBFile.delete();
            new File(deskDBFile.getParent(), deskDBFile.getName() + ".wal").delete();
        }
        
        // Delete H2 files
        if (h2File != null) {
            new File(h2File.getParent(), h2File.getName() + ".mv.db").delete();
            new File(h2File.getParent(), h2File.getName() + ".trace.db").delete();
        }
        
        // Delete SQLite file
        if (sqliteFile != null) {
            sqliteFile.delete();
        }
        
        // Delete HSQLDB files
        if (hsqldbFile != null) {
            new File(hsqldbFile.getParent(), hsqldbFile.getName() + ".script").delete();
            new File(hsqldbFile.getParent(), hsqldbFile.getName() + ".data").delete();
            new File(hsqldbFile.getParent(), hsqldbFile.getName() + ".log").delete();
            new File(hsqldbFile.getParent(), hsqldbFile.getName() + ".properties").delete();
        }
    }
    
    // Schema setup methods
    private void setupH2Schema() throws SQLException {
        try (Statement stmt = h2Conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INT PRIMARY KEY, " +
                    "name VARCHAR(255), " +
                    "email VARCHAR(255), " +
                    "age INT, " +
                    "balance DOUBLE)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)");
        }
    }
    
    private void setupSQLiteSchema() throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY, " +
                    "name TEXT, " +
                    "email TEXT, " +
                    "age INTEGER, " +
                    "balance REAL)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)");
        }
    }
    
    private void setupHSQLDBSchema() throws SQLException {
        try (Statement stmt = hsqldbConn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY, " +
                    "name VARCHAR(255), " +
                    "email VARCHAR(255), " +
                    "age INTEGER, " +
                    "balance DOUBLE)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)");
        }
    }
    
    // Clear methods
    private void clearDeskDB() {
        try {
            deskDB.table("users").delete().execute();
        } catch (Exception e) {
            // Table might not exist yet
        }
    }
    
    private void clearH2() throws SQLException {
        try (Statement stmt = h2Conn.createStatement()) {
            stmt.execute("DELETE FROM users");
        }
    }
    
    private void clearSQLite() throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("DELETE FROM users");
        }
    }
    
    private void clearHSQLDB() throws SQLException {
        try (Statement stmt = hsqldbConn.createStatement()) {
            stmt.execute("DELETE FROM users");
        }
    }
    
    // ==================== INSERT BENCHMARKS ====================
    
    @Benchmark
    public void insertSingle_DeskDB() {
        try {
            deskDB.table("users")
                .insert()
                .value("id", 1)
                .value("name", "Test User")
                .value("email", "test@example.com")
                .value("age", 30)
                .value("balance", 1000.50)
                .execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Benchmark
    public void insertSingle_H2() throws SQLException {
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "Test User");
            ps.setString(3, "test@example.com");
            ps.setInt(4, 30);
            ps.setDouble(5, 1000.50);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void insertSingle_SQLite() throws SQLException {
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "Test User");
            ps.setString(3, "test@example.com");
            ps.setInt(4, 30);
            ps.setDouble(5, 1000.50);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void insertSingle_HSQLDB() throws SQLException {
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "Test User");
            ps.setString(3, "test@example.com");
            ps.setInt(4, 30);
            ps.setDouble(5, 1000.50);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void insertBatch_DeskDB() {
        try (Transaction tx = deskDB.beginTransaction()) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                deskDB.table("users")
                    .insert()
                    .value("id", i)
                    .value("name", "User " + i)
                    .value("email", "user" + i + "@example.com")
                    .value("age", 20 + (i % 50))
                    .value("balance", 1000.0 + i * 1.5)
                    .execute();
            }
            tx.commit();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Benchmark
    public void insertBatch_H2() throws SQLException {
        h2Conn.setAutoCommit(false);
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + i);
                ps.setString(3, "user" + i + "@example.com");
                ps.setInt(4, 20 + (i % 50));
                ps.setDouble(5, 1000.0 + i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
            h2Conn.commit();
        } finally {
            h2Conn.setAutoCommit(true);
        }
    }
    
    @Benchmark
    public void insertBatch_SQLite() throws SQLException {
        sqliteConn.setAutoCommit(false);
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + i);
                ps.setString(3, "user" + i + "@example.com");
                ps.setInt(4, 20 + (i % 50));
                ps.setDouble(5, 1000.0 + i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
            sqliteConn.commit();
        } finally {
            sqliteConn.setAutoCommit(true);
        }
    }
    
    @Benchmark
    public void insertBatch_HSQLDB() throws SQLException {
        hsqldbConn.setAutoCommit(false);
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + i);
                ps.setString(3, "user" + i + "@example.com");
                ps.setInt(4, 20 + (i % 50));
                ps.setDouble(5, 1000.0 + i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
            hsqldbConn.commit();
        } finally {
            hsqldbConn.setAutoCommit(true);
        }
    }
    
    // ==================== READ BENCHMARKS ====================
    
    @Benchmark
    public void readPoint_DeskDB() {
        try {
            // First insert a record
            deskDB.table("users")
                .insert()
                .value("id", 999)
                .value("name", "Query Test")
                .value("email", "query@example.com")
                .value("age", 25)
                .value("balance", 500.0)
                .execute();
            
            List<Row> results = deskDB.table("users")
                .select()
                .where("id")
                .isEqualTo(999)
                .execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Benchmark
    public void readPoint_H2() throws SQLException {
        // First insert a record
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 999);
            ps.setString(2, "Query Test");
            ps.setString(3, "query@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 500.0);
            ps.executeUpdate();
        }
        
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, 999);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Consume result
                }
            }
        }
    }
    
    @Benchmark
    public void readPoint_SQLite() throws SQLException {
        // First insert a record
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 999);
            ps.setString(2, "Query Test");
            ps.setString(3, "query@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 500.0);
            ps.executeUpdate();
        }
        
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, 999);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Consume result
                }
            }
        }
    }
    
    @Benchmark
    public void readPoint_HSQLDB() throws SQLException {
        // First insert a record
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 999);
            ps.setString(2, "Query Test");
            ps.setString(3, "query@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 500.0);
            ps.executeUpdate();
        }
        
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, 999);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Consume result
                }
            }
        }
    }
    
    @Benchmark
    public void readRange_DeskDB() {
        try {
            // Insert test data
            try (Transaction tx = deskDB.beginTransaction()) {
                for (int i = 0; i < 100; i++) {
                    deskDB.table("users")
                        .insert()
                        .value("id", i)
                        .value("name", "User " + i)
                        .value("email", "user" + i + "@example.com")
                        .value("age", 20 + (i % 50))
                        .value("balance", 1000.0 + i * 1.5)
                        .execute();
                }
                tx.commit();
            }
            
            List<Row> results = deskDB.table("users")
                .select()
                .where("age")
                .greaterThanOrEqual(30)
                .and("age")
                .lessThanOrEqual(40)
                .orderBy("name")
                .limit(50)
                .execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Benchmark
    public void readRange_H2() throws SQLException {
        // Insert test data
        h2Conn.setAutoCommit(false);
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 100; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + i);
                ps.setString(3, "user" + i + "@example.com");
                ps.setInt(4, 20 + (i % 50));
                ps.setDouble(5, 1000.0 + i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
            h2Conn.commit();
        } finally {
            h2Conn.setAutoCommit(true);
        }
        
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "SELECT * FROM users WHERE age BETWEEN ? AND ? ORDER BY name LIMIT 50")) {
            ps.setInt(1, 30);
            ps.setInt(2, 40);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Consume result
                }
            }
        }
    }
    
    @Benchmark
    public void readRange_SQLite() throws SQLException {
        // Insert test data
        sqliteConn.setAutoCommit(false);
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 100; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + i);
                ps.setString(3, "user" + i + "@example.com");
                ps.setInt(4, 20 + (i % 50));
                ps.setDouble(5, 1000.0 + i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
            sqliteConn.commit();
        } finally {
            sqliteConn.setAutoCommit(true);
        }
        
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "SELECT * FROM users WHERE age BETWEEN ? AND ? ORDER BY name LIMIT 50")) {
            ps.setInt(1, 30);
            ps.setInt(2, 40);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Consume result
                }
            }
        }
    }
    
    @Benchmark
    public void readRange_HSQLDB() throws SQLException {
        // Insert test data
        hsqldbConn.setAutoCommit(false);
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 100; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + i);
                ps.setString(3, "user" + i + "@example.com");
                ps.setInt(4, 20 + (i % 50));
                ps.setDouble(5, 1000.0 + i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
            hsqldbConn.commit();
        } finally {
            hsqldbConn.setAutoCommit(true);
        }
        
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "SELECT * FROM users WHERE age BETWEEN ? AND ? ORDER BY name LIMIT 50")) {
            ps.setInt(1, 30);
            ps.setInt(2, 40);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Consume result
                }
            }
        }
    }
    
    // ==================== UPDATE BENCHMARKS ====================
    
    @Benchmark
    public void update_DeskDB() {
        try {
            // Insert first
            deskDB.table("users")
                .insert()
                .value("id", 888)
                .value("name", "Update Test")
                .value("email", "update@example.com")
                .value("age", 25)
                .value("balance", 100.0)
                .execute();
            
            // Then update
            deskDB.table("users")
                .update()
                .set("balance", 200.0)
                .where("id")
                .isEqualTo(888)
                .execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Benchmark
    public void update_H2() throws SQLException {
        // Insert first
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 888);
            ps.setString(2, "Update Test");
            ps.setString(3, "update@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 100.0);
            ps.executeUpdate();
        }
        
        // Then update
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "UPDATE users SET balance = ? WHERE id = ?")) {
            ps.setDouble(1, 200.0);
            ps.setInt(2, 888);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void update_SQLite() throws SQLException {
        // Insert first
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 888);
            ps.setString(2, "Update Test");
            ps.setString(3, "update@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 100.0);
            ps.executeUpdate();
        }
        
        // Then update
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "UPDATE users SET balance = ? WHERE id = ?")) {
            ps.setDouble(1, 200.0);
            ps.setInt(2, 888);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void update_HSQLDB() throws SQLException {
        // Insert first
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 888);
            ps.setString(2, "Update Test");
            ps.setString(3, "update@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 100.0);
            ps.executeUpdate();
        }
        
        // Then update
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "UPDATE users SET balance = ? WHERE id = ?")) {
            ps.setDouble(1, 200.0);
            ps.setInt(2, 888);
            ps.executeUpdate();
        }
    }
    
    // ==================== DELETE BENCHMARKS ====================
    
    @Benchmark
    public void delete_DeskDB() {
        try {
            // Insert first
            deskDB.table("users")
                .insert()
                .value("id", 777)
                .value("name", "Delete Test")
                .value("email", "delete@example.com")
                .value("age", 25)
                .value("balance", 100.0)
                .execute();
            
            // Then delete
            deskDB.table("users")
                .delete()
                .where("id")
                .isEqualTo(777)
                .execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Benchmark
    public void delete_H2() throws SQLException {
        // Insert first
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 777);
            ps.setString(2, "Delete Test");
            ps.setString(3, "delete@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 100.0);
            ps.executeUpdate();
        }
        
        // Then delete
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, 777);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void delete_SQLite() throws SQLException {
        // Insert first
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 777);
            ps.setString(2, "Delete Test");
            ps.setString(3, "delete@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 100.0);
            ps.executeUpdate();
        }
        
        // Then delete
        try (PreparedStatement ps = sqliteConn.prepareStatement(
                "DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, 777);
            ps.executeUpdate();
        }
    }
    
    @Benchmark
    public void delete_HSQLDB() throws SQLException {
        // Insert first
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "INSERT INTO users (id, name, email, age, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 777);
            ps.setString(2, "Delete Test");
            ps.setString(3, "delete@example.com");
            ps.setInt(4, 25);
            ps.setDouble(5, 100.0);
            ps.executeUpdate();
        }
        
        // Then delete
        try (PreparedStatement ps = hsqldbConn.prepareStatement(
                "DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, 777);
            ps.executeUpdate();
        }
    }
    
    // ==================== MAIN METHOD ====================
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(EmbeddedDatabaseBenchmark.class.getSimpleName())
            .warmupIterations(3)
            .measurementIterations(5)
            .forks(2)
            .warmupTime(TimeValue.seconds(2))
            .measurementTime(TimeValue.seconds(2))
            .build();
        
        new Runner(opt).run();
    }
}
