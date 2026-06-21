package com.deskdb.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punto de entrada principal para DeskDB.
 * Gestiona la apertura/cierre de la base de datos y proporciona acceso a las tablas.
 */
public class DeskDB implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DeskDB.class);

    private final Path dbPath;
    private final Path walPath;
    private final Map<String, Table> tables;
    private final Map<String, TableSchema> schemas;
    private boolean closed = false;

    private DeskDB(Path dbPath) throws IOException {
        this.dbPath = dbPath;
        this.walPath = dbPath.getParent().resolve(dbPath.getFileName().toString() + ".wal");
        this.tables = new ConcurrentHashMap<>();
        this.schemas = new HashMap<>();
        
        // Recuperar desde WAL si existe
        if (Files.exists(walPath)) {
            Transaction.recover(this, walPath);
        }
        
        if (Files.exists(dbPath)) {
            loadFromFile();
        } else {
            saveToFile();
        }
        
        logger.info("DeskDB opened at {}", dbPath.toAbsolutePath());
    }

    /**
     * Abre una base de datos DeskDB en la ruta especificada.
     * Si el archivo no existe, se crea uno nuevo.
     *
     * @param path Ruta al archivo .deskdb
     * @return Instancia de DeskDB
     * @throws IOException si hay un error de E/S
     */
    public static DeskDB open(String path) throws IOException {
        return open(Path.of(path));
    }

    /**
     * Abre una base de datos DeskDB en la ruta especificada.
     *
     * @param path Ruta al archivo .deskdb
     * @return Instancia de DeskDB
     * @throws IOException si hay un error de E/S
     */
    public static DeskDB open(Path path) throws IOException {
        if (!path.toString().endsWith(".deskdb")) {
            logger.warn("La ruta no termina en .deskdb, pero se procederá");
        }
        
        File parentDir = path.getParent() != null ? path.getParent().toFile() : new File(".");
        if (!parentDir.exists()) {
            Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
        }
        
        return new DeskDB(path);
    }

    /**
     * Obtiene una tabla por nombre para realizar operaciones.
     *
     * @param tableName Nombre de la tabla
     * @return TableOperations para realizar CRUD
     */
    public TableOperations table(String tableName) {
        checkClosed();
        return new TableOperations(this, tableName);
    }

    /**
     * Crea una tabla con el esquema especificado.
     *
     * @param tableName Nombre de la tabla
     * @param columns Columnas de la tabla
     * @return Table para operar con la tabla creada
     * @throws IOException si hay un error al crear la tabla
     */
    public Table createTable(String tableName, Column... columns) throws IOException {
        checkClosed();
        if (tables.containsKey(tableName)) {
            throw new IllegalStateException("La tabla '" + tableName + "' ya existe");
        }
        
        TableSchema schema = new TableSchema(tableName, List.of(columns));
        registerSchema(tableName, schema);
        
        Table table = new Table(tableName, List.of(columns), dbPath.toString());
        tables.put(tableName, table);
        
        logger.info("Tabla '{}' creada con {} columnas", tableName, columns.length);
        return table;
    }

    /**
     * Obtiene una tabla existente por nombre.
     *
     * @param tableName Nombre de la tabla
     * @return Table existente
     * @throws IllegalStateException si la tabla no existe
     */
    public Table getTable(String tableName) {
        checkClosed();
        Table table = tables.get(tableName);
        if (table == null) {
            throw new IllegalStateException("La tabla '" + tableName + "' no existe");
        }
        return table;
    }

    /**
     * Cierra la base de datos y persiste los datos en disco.
     *
     * @throws IOException si hay un error al guardar
     */
    public void close() throws IOException {
        if (!closed) {
            saveToFile();
            // Eliminar WAL si existe
            if (Files.exists(walPath)) {
                Files.delete(walPath);
            }
            closed = true;
            logger.info("DeskDB closed at {}", dbPath.toAbsolutePath());
        }
    }

    /**
     * Inicia una nueva transacción ACID.
     * 
     * @return Transacción para realizar operaciones atómicas
     * @throws IOException si hay un error al crear el WAL
     */
    public Transaction beginTransaction() throws IOException {
        checkClosed();
        return new Transaction(this, walPath);
    }

    /**
     * Obtiene el path del WAL.
     */
    Path getWalPath() {
        return walPath;
    }

    /**
     * Verifica si la base de datos está cerrada.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Obtiene el mapa de tablas interno.
     */
    Map<String, Table> getTables() {
        return tables;
    }

    /**
     * Obtiene el esquema de una tabla.
     */
    TableSchema getSchema(String tableName) {
        return schemas.get(tableName);
    }

    /**
     * Registra un esquema de tabla.
     */
    void registerSchema(String tableName, TableSchema schema) {
        schemas.put(tableName, schema);
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("DeskDB está cerrada");
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromFile() throws IOException {
        try {
            byte[] content = Files.readAllBytes(dbPath);
            if (content.length > 0) {
                Map<String, Object> loadedData = deserializeMap(content);
                
                // Cargar esquemas
                if (loadedData.containsKey("schemas")) {
                    Map<String, Object> schemasData = (Map<String, Object>) loadedData.get("schemas");
                    for (Map.Entry<String, Object> entry : schemasData.entrySet()) {
                        Map<String, Object> schemaData = (Map<String, Object>) entry.getValue();
                        TableSchema schema = TableSchema.fromMap(entry.getKey(), schemaData);
                        schemas.put(entry.getKey(), schema);
                    }
                }
                
                // Cargar datos legacy si existen (para compatibilidad)
                if (loadedData.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) loadedData.get("data");
                    logger.debug("Datos legacy cargados desde {}", dbPath);
                }
            }
        } catch (Exception e) {
            logger.warn("Error al cargar datos existentes, comenzando con DB vacía: {}", e.getMessage());
        }
    }

    private void saveToFile() throws IOException {
        synchronized (this) {
            Map<String, Object> data = new HashMap<>();
            
            // Guardar esquemas
            Map<String, Object> schemasData = new HashMap<>();
            for (Map.Entry<String, TableSchema> entry : schemas.entrySet()) {
                schemasData.put(entry.getKey(), entry.getValue().toMap());
            }
            data.put("schemas", schemasData);
            
            // Guardar en formato binario nativo
            byte[] content = serializeMap(data);
            Files.write(dbPath, content);
            logger.debug("Datos guardados en {}", dbPath);
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMap(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        Map<String, Object> result = (Map<String, Object>) ois.readObject();
        ois.close();
        return result;
    }
    
    private byte[] serializeMap(Map<String, Object> data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(data);
        oos.close();
        return baos.toByteArray();
    }
}
