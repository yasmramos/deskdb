package com.deskdb.jdbc;

import com.deskdb.core.DeskDB;
import com.deskdb.core.Transaction;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Implementación de Connection JDBC para DeskDB.
 */
public class DeskDBConnection implements Connection {

    private final DeskDB db;
    private final String dbPath;
    private boolean closed = false;
    private boolean autoCommit = true;
    private Transaction currentTransaction;
    private int isolationLevel = Connection.TRANSACTION_READ_COMMITTED;

    public DeskDBConnection(DeskDB db, String dbPath) {
        this.db = db;
        this.dbPath = dbPath;
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new DeskDBStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        return new DeskDBPreparedStatement(this, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("CallableStatement not supported");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return sql; // DeskDB usa su propio SQL-like syntax
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        this.autoCommit = autoCommit;
        
        if (!autoCommit && currentTransaction == null) {
            // Iniciar nueva transacción cuando se desactiva auto-commit
            currentTransaction = db.beginTransaction();
        } else if (autoCommit && currentTransaction != null) {
            // Commit y cerrar transacción actual si se activa auto-commit
            commit();
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkClosed();
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkClosed();
        if (currentTransaction != null) {
            try {
                currentTransaction.commit();
            } finally {
                currentTransaction = null;
            }
        }
        // En modo auto-commit, cada operación ya está commiteada
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
        if (currentTransaction != null) {
            try {
                currentTransaction.rollback();
            } finally {
                currentTransaction = null;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        
        // Rollback de transacción pendiente si existe
        if (currentTransaction != null) {
            try {
                currentTransaction.rollback();
            } catch (Exception e) {
                // Ignorar errores en rollback durante close
            }
            currentTransaction = null;
        }
        
        closed = true;
        // Nota: No cerramos la instancia DeskDB subyacente aquí
        // porque puede ser compartida entre múltiples conexiones
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();
        return new DeskDBDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkClosed();
        // DeskDB soporta lectura/escritura, ignoramos este flag por ahora
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkClosed();
        return false;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkClosed();
        // No implementado
    }

    @Override
    public String getCatalog() throws SQLException {
        checkClosed();
        return dbPath;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkClosed();
        this.isolationLevel = level;
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkClosed();
        return isolationLevel;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkClosed();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkClosed();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || 
            resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException("Only TYPE_FORWARD_ONLY and CONCUR_READ_ONLY supported");
        }
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || 
            resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException("Only TYPE_FORWARD_ONLY and CONCUR_READ_ONLY supported");
        }
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException("CallableStatement not supported");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("TypeMap not supported");
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("TypeMap not supported");
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Holdability not supported");
    }

    @Override
    public int getHoldability() throws SQLException {
        checkClosed();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException("CallableStatement not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkClosed();
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkClosed();
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkClosed();
        return prepareStatement(sql);
    }

    @Override
    public Clob createClob() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("CLOB not supported");
    }

    @Override
    public Blob createBlob() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("BLOB not supported");
    }

    @Override
    public NClob createNClob() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("NCLOB not supported");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("SQLXML not supported");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("Timeout must be >= 0");
        }
        return !closed && db != null && !db.isClosed();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (closed) {
            throw new SQLClientInfoException("Connection is closed", null);
        }
        // Ignorado - no implementado
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (closed) {
            throw new SQLClientInfoException("Connection is closed", null);
        }
        // Ignorado - no implementado
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkClosed();
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkClosed();
        return new Properties();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Arrays not supported");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Structs not supported");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkClosed();
        // No implementado
    }

    @Override
    public String getSchema() throws SQLException {
        checkClosed();
        return "PUBLIC";
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("Executor cannot be null");
        }
        close();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkClosed();
        throw new SQLFeatureNotSupportedException("Network timeout not supported");
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkClosed();
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (isWrapperFor(iface)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    /**
     * Obtiene la transacción actual, creando una nueva si es necesario.
     */
    public Transaction getOrCreateTransaction() {
        if (currentTransaction == null && !autoCommit) {
            currentTransaction = db.beginTransaction();
        }
        return currentTransaction;
    }

    /**
     * Obtiene la instancia de DeskDB subyacente.
     */
    public DeskDB getDeskDB() {
        return db;
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }
}
