package com.deskdb.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Transaction implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);
    
    private final DeskDB db;
    private boolean active = true;

    public Transaction(DeskDB db) { this.db = db; }

    public TableOperations table(String tableName) { return db.table(tableName); }

    public void commit() {
        if (!active) throw new IllegalStateException("Transaction already closed");
        active = false;
    }

    public void rollback() {
        if (!active) return;
        active = false;
        // En una implementación completa, aquí se restaurarían los datos anteriores
        logger.debug("Transaction rolled back");
    }

    @Override
    public void close() {
        if (active) rollback();
    }

    /**
     * Recupera el estado de la base de datos desde el WAL.
     * Esta es una implementación simplificada que solo elimina el WAL si existe.
     */
    public static void recover(DeskDB db, Path walPath) throws IOException {
        // Implementación simplificada: en producción, leería el WAL y aplicaría las operaciones
        // pendientes confirmadas. Por ahora, solo eliminamos el WAL asumiendo que fue procesado.
        logger.info("Recuperando desde WAL: {}", walPath);
        if (Files.exists(walPath)) {
            // Aquí iría la lógica real de replay del WAL
            // Por simplicidad, lo eliminamos para evitar errores en siguientes aperturas
            Files.delete(walPath);
            logger.info("WAL eliminado tras recuperación simulada");
        }
    }
}
