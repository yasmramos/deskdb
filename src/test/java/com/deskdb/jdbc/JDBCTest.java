package com.deskdb.jdbc;

import java.sql.*;

/**
 * Test de integración JDBC para DeskDB.
 * Demuestra el uso del driver JDBC con aplicaciones Java estándar.
 */
public class JDBCTest {

    public static void main(String[] args) {
        String url = "jdbc:deskdb:test_db";
        
        try {
            System.out.println("=== Test de Integración JDBC para DeskDB ===\n");
            
            // 1. Conectar usando DriverManager (auto-registro vía SPI)
            System.out.println("1. Conectando a DeskDB...");
            try (Connection conn = DriverManager.getConnection(url)) {
                System.out.println("   ✓ Conexión exitosa: " + conn.getMetaData().getDatabaseProductName());
                
                // 2. Crear tabla usando Statement
                System.out.println("\n2. Creando tabla 'empleados'...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE empleados");
                } catch (SQLException e) {
                    // Ignorar si no existe
                }
                
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(
                        "CREATE TABLE empleados (" +
                        "id INT, " +
                        "nombre VARCHAR, " +
                        "edad INT, " +
                        "salario DOUBLE)"
                    );
                    System.out.println("   ✓ Tabla creada exitosamente");
                }
                
                // 3. Insertar datos usando PreparedStatement
                System.out.println("\n3. Insertando datos con PreparedStatement...");
                String insertSQL = "INSERT INTO empleados (id, nombre, edad, salario) VALUES (?, ?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    pstmt.setInt(1, 1);
                    pstmt.setString(2, "Juan Pérez");
                    pstmt.setInt(3, 30);
                    pstmt.setDouble(4, 50000.50);
                    pstmt.executeUpdate();
                    
                    pstmt.setInt(1, 2);
                    pstmt.setString(2, "María García");
                    pstmt.setInt(3, 25);
                    pstmt.setDouble(4, 45000.75);
                    pstmt.executeUpdate();
                    
                    pstmt.setInt(1, 3);
                    pstmt.setString(2, "Carlos López");
                    pstmt.setInt(3, 35);
                    pstmt.setDouble(4, 60000.00);
                    pstmt.executeUpdate();
                    
                    System.out.println("   ✓ 3 registros insertados");
                }
                
                // 4. Consultar datos usando Statement
                System.out.println("\n4. Consultando datos con Statement...");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM empleados")) {
                    
                    System.out.println("   Resultados:");
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        String nombre = rs.getString("nombre");
                        int edad = rs.getInt("edad");
                        double salario = rs.getDouble("salario");
                        
                        System.out.printf("   - ID: %d, Nombre: %s, Edad: %d, Salario: $%.2f%n", 
                                        id, nombre, edad, salario);
                    }
                }
                
                // 5. Consultar con PreparedStatement y parámetros
                System.out.println("\n5. Consulta con PreparedStatement (edad > ?)...");
                String selectSQL = "SELECT * FROM empleados WHERE edad > ?";
                try (PreparedStatement pstmt = conn.prepareStatement(selectSQL)) {
                    pstmt.setInt(1, 28);
                    
                    try (ResultSet rs = pstmt.executeQuery()) {
                        System.out.println("   Empleados mayores de 28 años:");
                        while (rs.next()) {
                            System.out.printf("   - %s (%d años)%n", 
                                            rs.getString("nombre"), 
                                            rs.getInt("edad"));
                        }
                    }
                }
                
                // 6. Probar transacciones
                System.out.println("\n6. Probando transacciones...");
                conn.setAutoCommit(false);
                
                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    pstmt.setInt(1, 4);
                    pstmt.setString(2, "Ana Martínez");
                    pstmt.setInt(3, 28);
                    pstmt.setDouble(4, 52000.00);
                    pstmt.executeUpdate();
                    System.out.println("   ✓ Registro insertado (no confirmado aún)");
                    
                    // Rollback
                    conn.rollback();
                    System.out.println("   ✓ Rollback realizado");
                }
                
                // Verificar que el registro no está
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as total FROM empleados")) {
                    rs.next();
                    int count = rs.getInt("total");
                    System.out.println("   ✓ Total de registros después de rollback: " + count);
                }
                
                // Commit de una nueva inserción
                conn.setAutoCommit(true);
                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    pstmt.setInt(1, 5);
                    pstmt.setString(2, "Luis Rodríguez");
                    pstmt.setInt(3, 40);
                    pstmt.setDouble(4, 70000.00);
                    pstmt.executeUpdate();
                    System.out.println("   ✓ Nuevo registro insertado y confirmado (auto-commit)");
                }
                
                // 7. Metadata de la base de datos
                System.out.println("\n7. Información de metadata...");
                DatabaseMetaData meta = conn.getMetaData();
                System.out.println("   - Producto: " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
                System.out.println("   - Driver: " + meta.getDriverName() + " v" + meta.getDriverVersion());
                System.out.println("   - Soporta transacciones: " + meta.supportsTransactions());
                System.out.println("   - Archivos locales: " + meta.usesLocalFiles());
                
                System.out.println("\n=== ¡Todos los tests JDBC completados exitosamente! ===");
            }
            
            // Shutdown del driver
            DeskDBDriver.shutdown();
            System.out.println("\nDriver shutdown completado.");
            
        } catch (SQLException e) {
            System.err.println("Error SQL: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Error general: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
