package com.deskdb.jdbc;

import com.deskdb.core.DeskDB;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver JDBC para DeskDB.
 * Permite conectar aplicaciones Java estándar a la base de datos DeskDB
 * usando la URL: jdbc:deskdb:<ruta_archivo>
 */
public class DeskDBDriver implements Driver {

    private static final ConcurrentHashMap<String, DeskDB> connections = new ConcurrentHashMap<>();
    private static final String URL_PREFIX = "jdbc:deskdb:";

    static {
        try {
            DriverManager.registerDriver(new DeskDBDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register DeskDB driver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        try {
            String dbPath = url.substring(URL_PREFIX.length());
            
            // Si ya existe una conexión activa, reutilizarla
            DeskDB existingDb = connections.get(dbPath);
            if (existingDb != null && !existingDb.isClosed()) {
                return new DeskDBConnection(existingDb, dbPath);
            }

            // Crear nueva instancia de DeskDB
            DeskDB db = DeskDB.open(dbPath);
            connections.put(dbPath, db);
            
            return new DeskDBConnection(db, dbPath);
        } catch (Exception e) {
            throw new SQLException("Failed to connect to DeskDB: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false; // Partial JDBC implementation
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
        return java.util.logging.Logger.getLogger(java.util.logging.Logger.GLOBAL_LOGGER_NAME);
    }

    /**
     * Cierra todas las conexiones activas y libera recursos.
     * Debe llamarse al finalizar la aplicación.
     */
    public static void shutdown() {
        for (DeskDB db : connections.values()) {
            try {
                if (db != null && !db.isClosed()) {
                    db.close();
                }
            } catch (Exception e) {
                // Logear pero continuar cerrando otras conexiones
            }
        }
        connections.clear();
    }

    /**
     * Obtiene la instancia de DeskDB subyacente para una URL dada.
     * Útil para operaciones administrativas.
     */
    public static DeskDB getDatabase(String url) {
        if (!url.startsWith(URL_PREFIX)) {
            return null;
        }
        String dbPath = url.substring(URL_PREFIX.length());
        return connections.get(dbPath);
    }
}
