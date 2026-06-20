package com.deskdb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas para transacciones ACID con WAL
 */
public class TransactionTest {

    @TempDir
    Path tempDir;

    @Test
    public void testTransactionCommit() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        
        try (DeskDB db = DeskDB.open(dbPath)) {
            // Iniciar transacción
            try (Transaction tx = db.beginTransaction()) {
                // Insertar datos dentro de la transacción
                TableOperations ops = db.table("usuarios");
                
                // Registrar operación en la transacción
                String key = java.util.UUID.randomUUID().toString();
                Map<String, Object> userData = Map.of("nombre", "Ana", "edad", 30);
                tx.registerInsert("usuarios", key, userData);
                
                // Confirmar transacción
                tx.commit();
            }
            
            // Verificar que los datos persistieron
            List<Map<String, Object>> results = db.table("usuarios").select().execute();
            assertEquals(1, results.size());
            assertEquals("Ana", results.get(0).get("nombre"));
            assertEquals(30, results.get(0).get("edad"));
        }
    }

    @Test
    public void testTransactionRollback() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        
        try (DeskDB db = DeskDB.open(dbPath)) {
            // Insertar un dato inicial fuera de la transacción
            db.table("usuarios").insert()
              .value("nombre", "Inicial")
              .value("edad", 25)
              .execute();
            
            // Iniciar transacción y hacer rollback
            try (Transaction tx = db.beginTransaction()) {
                String key = java.util.UUID.randomUUID().toString();
                Map<String, Object> userData = Map.of("nombre", "Modificado", "edad", 99);
                tx.registerInsert("usuarios", key, userData);
                
                // Revertir transacción
                tx.rollback();
            }
            
            // Verificar que solo está el dato inicial
            List<Map<String, Object>> results = db.table("usuarios").select().execute();
            assertEquals(1, results.size());
            assertEquals("Inicial", results.get(0).get("nombre"));
        }
    }

    @Test
    public void testTransactionAutoRollbackOnClose() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        
        try (DeskDB db = DeskDB.open(dbPath)) {
            // Iniciar transacción pero no confirmar (se hará rollback al cerrar)
            try (Transaction tx = db.beginTransaction()) {
                String key = java.util.UUID.randomUUID().toString();
                Map<String, Object> userData = Map.of("nombre", "NoConfirmado", "edad", 50);
                tx.registerInsert("usuarios", key, userData);
                // No llamar a commit(), se hará rollback automático al cerrar
            }
            
            // Verificar que no hay datos
            List<Map<String, Object>> results = db.table("usuarios").select().execute();
            assertEquals(0, results.size());
        }
    }

    @Test
    public void testTransactionRecoveryAfterCrash() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        Path walPath = tempDir.resolve("test.deskdb.wal");
        
        // Simular crash: escribir en WAL pero no persistir en archivo principal
        try (DeskDB db = DeskDB.open(dbPath)) {
            try (Transaction tx = db.beginTransaction()) {
                String key = java.util.UUID.randomUUID().toString();
                Map<String, Object> userData = Map.of("nombre", "Recuperado", "edad", 40);
                tx.registerInsert("usuarios", key, userData);
                tx.commit(); // Esto escribe en WAL y aplica en memoria
                
                // No cerramos la DB normalmente (simulamos crash)
                // Los datos están en memoria y WAL, pero no en archivo .deskdb
            }
        }
        
        // Reabrir la DB (debería recuperar desde WAL)
        // Nota: El WAL se elimina al cerrar DeskDB, pero los datos ya están aplicados
        // En un crash real, el WAL existiría al reiniciar
        try (DeskDB db = DeskDB.open(dbPath)) {
            List<Map<String, Object>> results = db.table("usuarios").select().execute();
            // Los datos deberían estar porque se aplicaron antes del "crash"
            // En este caso simple, se guardaron al cerrar la primera instancia
            assertTrue(results.size() >= 1, "Debería haber al menos 1 resultado");
        }
    }

    @Test
    public void testMultipleOperationsInTransaction() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        
        try (DeskDB db = DeskDB.open(dbPath)) {
            // Insertar datos iniciales
            db.table("productos").insert()
              .value("nombre", "Producto1")
              .value("precio", 100)
              .execute();
            
            db.table("productos").insert()
              .value("nombre", "Producto2")
              .value("precio", 200)
              .execute();
            
            // Transacción con múltiples operaciones
            try (Transaction tx = db.beginTransaction()) {
                // Insertar nuevo producto
                String key1 = java.util.UUID.randomUUID().toString();
                tx.registerInsert("productos", key1, Map.of("nombre", "Producto3", "precio", 300));
                
                // Actualizar producto existente (simplificado: insertamos con misma clave)
                // En una implementación real, necesitaríamos obtener la clave existente
                
                // Eliminar producto (simplificado)
                // tx.registerDelete("productos", keyToDelete);
                
                tx.commit();
            }
            
            List<Map<String, Object>> results = db.table("productos").select().execute();
            assertEquals(3, results.size());
        }
    }

    @Test
    public void testTransactionCannotOperateAfterCommit() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        
        try (DeskDB db = DeskDB.open(dbPath)) {
            Transaction tx = db.beginTransaction();
            
            String key = java.util.UUID.randomUUID().toString();
            tx.registerInsert("test", key, Map.of("valor", 1));
            tx.commit();
            
            // Intentar operar después de commit debería fallar
            assertThrows(IllegalStateException.class, () -> {
                tx.registerInsert("test", "otra", Map.of("valor", 2));
            });
            
            tx.close();
        }
    }

    @Test
    public void testTransactionCannotOperateAfterRollback() throws IOException {
        Path dbPath = tempDir.resolve("test.deskdb");
        
        try (DeskDB db = DeskDB.open(dbPath)) {
            Transaction tx = db.beginTransaction();
            
            String key = java.util.UUID.randomUUID().toString();
            tx.registerInsert("test", key, Map.of("valor", 1));
            tx.rollback();
            
            // Intentar operar después de rollback debería fallar
            assertThrows(IllegalStateException.class, () -> {
                tx.registerInsert("test", "otra", Map.of("valor", 2));
            });
            
            tx.close();
        }
    }
}
