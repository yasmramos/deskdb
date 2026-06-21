package com.deskdb.transaction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Control de Concurrencia Multi-Versión (MVCC).
 * Permite lecturas no bloqueantes y escrituras aisladas.
 */
public class MVCC {
    
    // Versión global que incrementa con cada transacción
    private final AtomicLong globalVersion = new AtomicLong(0);
    
    // Almacena múltiples versiones de cada fila: rowId -> lista de versiones
    private final Map<Long, RowVersion> rowVersions = new ConcurrentHashMap<>();
    
    // Lock para operaciones de escritura en el mapa de versiones
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * Representa una versión de una fila.
     */
    public static class RowVersion {
        public final long version;
        public final long timestamp;
        public final Map<String, Object> data;
        public final boolean deleted;
        
        public RowVersion(long version, long timestamp, Map<String, Object> data, boolean deleted) {
            this.version = version;
            this.timestamp = timestamp;
            this.data = data;
            this.deleted = deleted;
        }
    }
    
    /**
     * Comienza una nueva transacción y retorna la versión de snapshot.
     */
    public long beginTransaction() {
        return globalVersion.get();
    }
    
    /**
     * Lee una fila para una transacción específica (snapshot isolation).
     * @param rowId ID de la fila
     * @param transactionVersion Versión de la transacción (snapshot)
     * @return Los datos de la fila o null si no existe/fue eliminada
     */
    public Map<String, Object> read(long rowId, long transactionVersion) {
        lock.readLock().lock();
        try {
            RowVersion rowVersion = rowVersions.get(rowId);
            if (rowVersion == null) {
                return null;
            }
            
            // Encontrar la versión más reciente visible para esta transacción
            RowVersion visibleVersion = findVisibleVersion(rowVersion, transactionVersion);
            
            if (visibleVersion == null || visibleVersion.deleted) {
                return null;
            }
            
            return visibleVersion.data;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Escribe una nueva versión de una fila.
     * @param rowId ID de la fila
     * @param data Datos de la fila
     * @param transactionVersion Versión de la transacción que escribe
     */
    public void write(long rowId, Map<String, Object> data, long transactionVersion) {
        lock.writeLock().lock();
        try {
            long newVersion = globalVersion.incrementAndGet();
            long timestamp = System.currentTimeMillis();
            
            RowVersion newRowVersion = new RowVersion(newVersion, timestamp, data, false);
            
            // Encadenar versiones (en una implementación completa, sería una lista)
            // Aquí simplificamos manteniendo solo la última versión
            rowVersions.put(rowId, newRowVersion);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Elimina una fila (marca como deleted).
     * @param rowId ID de la fila
     * @param transactionVersion Versión de la transacción
     */
    public void delete(long rowId, long transactionVersion) {
        lock.writeLock().lock();
        try {
            long newVersion = globalVersion.incrementAndGet();
            long timestamp = System.currentTimeMillis();
            
            RowVersion currentVersion = rowVersions.get(rowId);
            Map<String, Object> data = (currentVersion != null) ? currentVersion.data : new ConcurrentHashMap<>();
            
            RowVersion deletedVersion = new RowVersion(newVersion, timestamp, data, true);
            rowVersions.put(rowId, deletedVersion);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Encuentra la versión visible para una transacción dada.
     */
    private RowVersion findVisibleVersion(RowVersion rowVersion, long transactionVersion) {
        // En una implementación completa, recorreríamos la cadena de versiones
        // Aquí simplificamos: si la versión es <= transactionVersion, es visible
        if (rowVersion.version <= transactionVersion) {
            return rowVersion;
        }
        return null;
    }
    
    /**
     * Obtiene la versión global actual.
     */
    public long getGlobalVersion() {
        return globalVersion.get();
    }
    
    /**
     * Limpia versiones antiguas (vacuum).
     * @param minVisibleVersion Versión mínima que debe mantenerse
     */
    public void vacuum(long minVisibleVersion) {
        lock.writeLock().lock();
        try {
            // En una implementación completa, eliminaríamos versiones demasiado antiguas
            // que ya no son visibles para ninguna transacción activa
            // Aquí es un placeholder para futura optimización
        } finally {
            lock.writeLock().unlock();
        }
    }
}
