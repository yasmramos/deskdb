package com.deskdb.pool;

import com.deskdb.core.DeskDB;
import com.deskdb.jdbc.DeskDBConnection;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native connection pool for DeskDB.
 * Provides efficient connection management for concurrent applications.
 */
public class DeskDBConnectionPool {
    
    private final BlockingQueue<Connection> pool;
    private final String jdbcUrl;
    private final int poolSize;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    
    /**
     * Creates a new connection pool.
     * 
     * @param jdbcUrl JDBC URL for the database
     * @param poolSize Maximum number of connections in the pool
     * @throws SQLException if pool initialization fails
     */
    public DeskDBConnectionPool(String jdbcUrl, int poolSize) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.poolSize = poolSize;
        this.pool = new LinkedBlockingQueue<>(poolSize);
        
        // Initialize pool with connections
        for (int i = 0; i < poolSize; i++) {
            try {
                // Extract DB path from JDBC URL (jdbc:deskdb:/path/to/db)
                String dbPath = jdbcUrl.replace("jdbc:deskdb:", "");
                DeskDB db = DeskDB.open(dbPath);
                Connection conn = new DeskDBConnection(db, dbPath);
                pool.offer(conn);
            } catch (Exception e) {
                throw new SQLException("Failed to initialize connection pool", e);
            }
        }
    }
    
    /**
     * Acquires a connection from the pool.
     * Blocks if no connections are available.
     * 
     * @return A database connection
     * @throws SQLException if pool is closed or interrupted
     */
    public Connection getConnection() throws SQLException {
        if (isClosed.get()) {
            throw new SQLException("Connection pool is closed");
        }
        
        try {
            Connection conn = pool.take();
            
            // Validate connection before returning
            if (conn.isClosed()) {
                // Replace with new connection
                try {
                    String dbPath = jdbcUrl.replace("jdbc:deskdb:", "");
                    DeskDB db = DeskDB.open(dbPath);
                    conn = new DeskDBConnection(db, dbPath);
                } catch (IOException e) {
                    throw new SQLException("Failed to create new connection", e);
                }
            }
            
            return conn;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for connection", e);
        }
    }
    
    /**
     * Returns a connection to the pool.
     * Should be called in finally blocks after using connections.
     * 
     * @param connection The connection to return
     */
    public void releaseConnection(Connection connection) {
        if (connection != null && !isClosed.get()) {
            try {
                if (!connection.isClosed()) {
                    // Reset connection state if needed
                    if (connection.getAutoCommit() == false) {
                        connection.setAutoCommit(true);
                    }
                    pool.offer(connection);
                }
            } catch (SQLException e) {
                // Discard problematic connection
                try {
                    connection.close();
                } catch (SQLException ex) {
                    // Ignore
                }
            }
        }
    }
    
    /**
     * Closes the pool and all connections.
     */
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            Connection conn;
            while ((conn = pool.poll()) != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Log but continue closing other connections
                }
            }
        }
    }
    
    /**
     * Returns the current number of available connections.
     * 
     * @return Number of available connections
     */
    public int getAvailableConnections() {
        return pool.size();
    }
    
    /**
     * Returns the total pool size.
     * 
     * @return Pool size
     */
    public int getPoolSize() {
        return poolSize;
    }
}
