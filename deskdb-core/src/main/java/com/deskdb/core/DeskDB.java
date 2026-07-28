package com.deskdb.core;

import com.deskdb.index.BTree;
import com.deskdb.mapping.ObjectStore;
import com.deskdb.storage.Wal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Punto de entrada principal para DeskDB.
 * Gestiona la apertura/cierre de la base de datos y proporciona acceso a las tablas.
 * TODO EL CONTENIDO SE GUARDA EN UN SOLO ARCHIVO .deskdb (como H2)
 */
public class DeskDB implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DeskDB.class);
    
    // ThreadLocal para rastrear transacciones activas y evitar anidamiento
    private static final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();

    private final Path dbPath;
    private final Map<String, Table> tables;
    private final Map<String, TableSchema> schemas;
    private final Map<String, Map<String, BTree<?, ?>>> indexes; // tableName -> indexName -> BTree
    private final ObjectStore objectStore; // Single shared instance for object persistence
    private Wal wal;
    private boolean closed = false;
    private final AtomicInteger transactionCounter = new AtomicInteger(0);
    private final ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock();

    public String getFilePath() {
        return dbPath.toString();
    }

    private DeskDB(Path dbPath) throws IOException {
        this.dbPath = dbPath;
        this.tables = new ConcurrentHashMap<>();
        this.schemas = new HashMap<>();
        this.indexes = new ConcurrentHashMap<>();
        this.objectStore = new ObjectStore(this); // Initialize shared ObjectStore with integrated storage
        
        // Determinar ruta del WAL (mismo directorio que el archivo .deskdb)
        Path walPath = dbPath.resolveSibling(dbPath.getFileName().toString() + ".wal");
        
        if (Files.exists(dbPath)) {
            loadFromFile();
            
            // Recuperar desde WAL si existe
            try {
                Transaction.recover(this, walPath);
            } catch (Exception e) {
                logger.warn("Recovery failed: {}", e.getMessage());
            }
        } else {
            // Crear directorio padre si no existe
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            saveToFile();
        }
        
        // Inicializar WAL
        this.wal = Wal.open(walPath);
        
        logger.info("DeskDB opened at {} with WAL at {}", dbPath.toAbsolutePath(), walPath.toAbsolutePath());
    }
    
    /**
     * Creates an in-memory only DeskDB instance.
     * All data (both SQL tables and objects) will be lost when the instance is closed or the JVM exits.
     * This is useful for testing or temporary data storage.
     *
     * @return In-memory DeskDB instance
     * @throws IOException if there is an IO error
     */
    public static DeskDB inMemory() throws IOException {
        // Create a temporary path that won't be persisted
        Path tempPath = Files.createTempFile("deskdb_inmemory_", ".deskdb");
        tempPath.toFile().deleteOnExit();
        
        DeskDB db = new DeskDB(tempPath, true);
        logger.info("In-memory DeskDB created");
        return db;
    }
    
    // Private constructor for in-memory mode
    private DeskDB(Path dbPath, boolean inMemoryOnly) throws IOException {
        this.dbPath = dbPath;
        this.tables = new ConcurrentHashMap<>();
        this.schemas = new HashMap<>();
        this.indexes = new ConcurrentHashMap<>();
        this.objectStore = new ObjectStore(this, true); // Initialize ObjectStore with in-memory flag
        this.wal = null; // No WAL for in-memory mode
        
        logger.info("In-memory DeskDB initialized (no disk persistence)");
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
     * Si existe una transacción activa en este hilo, la utiliza automáticamente.
     *
     * @param tableName Nombre de la tabla
     * @return TableOperations para realizar CRUD
     */
    public TableOperations table(String tableName) {
        checkClosed();
        // Reutilizar transacción activa si existe
        Transaction existingTx = currentTransaction.get();
        if (existingTx != null) {
            return new TableOperations(this, tableName, existingTx);
        }
        return new TableOperations(this, tableName);
    }

    /**
     * Obtiene una tabla por nombre para realizar operaciones dentro de una transacción.
     */
    public TableOperations table(String tableName, Transaction transaction) {
        checkClosed();
        return new TableOperations(this, tableName, transaction);
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
        table.setDb(this);
        tables.put(tableName, table);
        indexes.put(tableName, new ConcurrentHashMap<>());
        
        // Crear índice automático para primary key
        for (Column col : columns) {
            if (col.isPrimaryKey()) {
                createIndex(tableName, col.getName() + "_idx", col.getName());
            }
        }
        
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
            // Save data to file first
            saveToFile();
            
            // Close and truncate WAL since data is now persisted
            if (wal != null) {
                wal.close();
                // Truncate WAL file after successful persistence
                try {
                    java.nio.file.Files.deleteIfExists(wal.getWalPath());
                } catch (Exception e) {
                    logger.warn("Could not delete WAL file: {}", e.getMessage());
                }
            }
            closed = true;
            logger.info("DeskDB closed at {}", dbPath.toAbsolutePath());
        }
    }
    
    /**
     * Obtiene el WAL de la base de datos.
     * @return El WAL instance
     */
    public Wal getWal() {
        return wal;
    }

    /**
     * Inicia una nueva transacción ACID.
     * Si ya existe una transacción activa en este hilo, la devuelve (evita anidamiento).
     * 
     * @return Transacción para realizar operaciones atómicas
     */
    public Transaction beginTransaction() throws IOException {
        return beginTransaction(false);
    }
    
    /**
     * Starts a new transaction with explicit commit/rollback control.
     * 
     * @param autoCommit if true, changes are committed automatically (implicit transaction)
     *                   if false, user must explicitly call commit() or rollback()
     * @return Transaction for performing atomic operations
     * @throws IOException if database is closed
     */
    public Transaction beginTransaction(boolean autoCommit) throws IOException {
        checkClosed();
        
        // For explicit transactions (autoCommit=false), don't reuse existing transactions
        // This allows multiple explicit transactions to run concurrently for isolation testing
        if (autoCommit) {
            // If there's already an active implicit transaction in this thread, return it to avoid nesting
            Transaction existingTx = currentTransaction.get();
            if (existingTx != null) {
                logger.debug("Reusing existing transaction in thread {}", Thread.currentThread().getName());
                return existingTx;
            }
        }
        
        Transaction tx = new Transaction(this, !autoCommit);
        if (!autoCommit) {
            currentTransaction.set(tx);
        }
        logger.debug("Started new transaction {} in thread {} (autoCommit={})", 
            transactionCounter.incrementAndGet(), Thread.currentThread().getName(), autoCommit);
        return tx;
    }
    
    /**
     * Obtiene la transacción activa actual en este hilo, si existe.
     * @return La transacción activa o null si no hay ninguna
     */
    public Transaction getCurrentTransaction() {
        return currentTransaction.get();
    }
    
    /**
     * Libera la transacción activa del hilo actual (llamado internamente tras commit/rollback).
     */
    void releaseCurrentTransaction() {
        currentTransaction.remove();
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
     * Obtiene los datos internos de una tabla para operaciones de guardado.
     * Package-private para evitar acceso externo pero permitir DeskDB guardar datos.
     */
    Map<Long, Row> getTableData(Table table) {
        return table.data;
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

    /**
     * Creates an ObjectStore for object persistence operations.
     * 
     * @return ObjectStore instance for object storage operations
     */
    public ObjectStore createObjectStore() {
        checkClosed();
        return new ObjectStore(this);
    }

    /**
     * Finds an entity by its ID using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.find().
     *
     * @param clazz The entity class
     * @param id The ID to search for
     * @return The entity or null if not found
     */
    public <T> T find(Class<T> clazz, Object id) {
        checkClosed();
        return objectStore.find(clazz, id);
    }

    /**
     * Persists an entity using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.persist().
     *
     * @param entity The entity to persist
     * @return The generated ID
     */
    public <T> Object persist(T entity) {
        checkClosed();
        return objectStore.persist(entity);
    }

    /**
     * Persists an entity with a specific ID using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.persist(entity, id).
     *
     * @param entity The entity to persist
     * @param id The ID to use
     */
    public <T> void persist(T entity, Object id) {
        checkClosed();
        objectStore.persist(entity, id);
    }

    /**
     * Finds all entities of a given type using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.findAll().
     *
     * @param clazz The entity class
     * @return List of all entities of that type
     */
    public <T> List<T> findAll(Class<T> clazz) {
        checkClosed();
        return objectStore.findAll(clazz);
    }

    /**
     * Deletes an entity by its ID using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.remove().
     *
     * @param clazz The entity class
     * @param id The ID to delete
     * @return true if the entity was deleted
     */
    public <T> boolean remove(Class<T> clazz, Object id) {
        checkClosed();
        return objectStore.remove(clazz, id);
    }

    /**
     * Deletes an entity using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.remove().
     *
     * @param entity The entity to delete
     * @return true if the entity was deleted
     */
    public <T> boolean remove(T entity) {
        checkClosed();
        return objectStore.remove(entity);
    }

    /**
     * Updates an existing entity using the ObjectStore.
     * This is a convenience method that delegates to ObjectStore.update().
     *
     * @param entity The entity to update
     */
    public <T> void update(T entity) {
        checkClosed();
        objectStore.update(entity);
    }

    /**
     * Creates a BTree index on a specific column.
     */
    public <K extends Comparable<K>> void createIndex(String tableName, String indexName, String columnName) throws IOException {
        createIndex(tableName, indexName, columnName, false);
    }

    /**
     * Creates an index on a column (single column).
     */
    @SuppressWarnings("unchecked")
    public <K extends Comparable<K>> void createIndex(String tableName, String indexName, String columnName, boolean unique) throws IOException {
        checkClosed();
        Map<String, BTree<?, ?>> tableIndexes = indexes.computeIfAbsent(tableName, k -> new ConcurrentHashMap<>());
        BTree<K, Long> btree = new BTree<>(indexName);
        tableIndexes.put(indexName, btree);
        
        // Index existing data
        Table table = tables.get(tableName);
        if (table != null) {
            for (Row row : table.select(null)) {
                Object value = row.get(columnName);
                if (value != null) {
                    btree.insert((K) value, row.getRowId());
                }
            }
        }
        logger.info("Index '{}' created on table '{}' for column '{}' (unique={})", indexName, tableName, columnName, unique);
    }

    /**
     * Creates an index on one or more columns with optional uniqueness constraint.
     * For composite indexes, columnList should be comma-separated column names.
     * This method is package-private to avoid name clash with the generic version.
     */
    void createIndexInternal(String tableName, String indexName, String columnList, boolean unique) throws IOException {
        checkClosed();
        Map<String, BTree<?, ?>> tableIndexes = indexes.computeIfAbsent(tableName, k -> new ConcurrentHashMap<>());
        
        // Use raw type to avoid generic bounds issues with composite keys
        @SuppressWarnings("rawtypes")
        BTree btree = new BTree(indexName);
        tableIndexes.put(indexName, btree);
        
        // Index existing data
        Table table = tables.get(tableName);
        if (table != null) {
            String[] columns = columnList.split(",");
            for (Row row : table.select(null)) {
                // For composite indexes, create a composite key
                Object keyValue;
                if (columns.length == 1) {
                    keyValue = row.get(columns[0].trim());
                } else {
                    // Create composite key as concatenated string
                    StringBuilder keyBuilder = new StringBuilder();
                    for (int i = 0; i < columns.length; i++) {
                        if (i > 0) keyBuilder.append("|");
                        Object val = row.get(columns[i].trim());
                        keyBuilder.append(val != null ? val.toString() : "");
                    }
                    keyValue = keyBuilder.toString();
                }
                
                if (keyValue != null) {
                    btree.insert((String) keyValue, row.getRowId());
                }
            }
        }
        logger.info("Index '{}' created on table '{}' for column(s) '{}' (unique={})", indexName, tableName, columnList, unique);
    }

    /**
     * Gets an index by table and index name.
     */
    @SuppressWarnings("unchecked")
    public <K extends Comparable<K>> BTree<K, Long> getIndex(String tableName, String indexName) {
        Map<String, BTree<?, ?>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null) return null;
        return (BTree<K, Long>) tableIndexes.get(indexName);
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("DeskDB está cerrada");
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromFile() throws IOException {
        // Use read lock to allow concurrent reads during load
        dbLock.readLock().lock();
        try {
            byte[] content = Files.readAllBytes(dbPath);
            if (content.length > 0) {
                ByteArrayInputStream bais = new ByteArrayInputStream(content);
                DataInputStream in = new DataInputStream(bais);
                
                // Read number of schemas
                int schemaCount = in.readInt();
                for (int i = 0; i < schemaCount; i++) {
                    String tableName = in.readUTF();
                    int columnCount = in.readInt();
                    Column[] columns = new Column[columnCount];
                    for (int j = 0; j < columnCount; j++) {
                        String colName = in.readUTF();
                        DataType dataType = DataType.valueOf(in.readUTF());
                        boolean primaryKey = in.readBoolean();
                        boolean notNull = in.readBoolean();
                        columns[j] = new Column(colName, dataType);
                        if (primaryKey) columns[j].setPrimaryKey(true);
                        if (notNull) columns[j].setNotNull(true);
                    }
                    TableSchema schema = new TableSchema(tableName, List.of(columns));
                    schemas.put(tableName, schema);
                    
                    // Create table
                    Table table = new Table(tableName, List.of(columns), dbPath.toString());
                    table.setDb(this);
                    tables.put(tableName, table);
                }
                
                // Read data if exists
                if (in.available() >= 4) {
                    int dataLength = in.readInt();
                    if (dataLength > 0 && in.available() >= dataLength) {
                        byte[] dataContent = new byte[dataLength];
                        in.readFully(dataContent);
                        
                        ByteArrayInputStream dataBais = new ByteArrayInputStream(dataContent);
                        DataInputStream dataIn = new DataInputStream(dataBais);
                        
                        // Read data from each table
                        while (dataIn.available() > 0) {
                            String tableName = dataIn.readUTF();
                            int rowCount = dataIn.readInt();
                            
                            Table table = tables.get(tableName);
                            if (table != null) {
                                for (int i = 0; i < rowCount; i++) {
                                    long rowId = dataIn.readLong();
                                    int valueCount = dataIn.readInt();
                                    Map<String, Object> values = new HashMap<>();
                                    
                                    for (int j = 0; j < valueCount; j++) {
                                        String key = dataIn.readUTF();
                                        Object value = readValue(dataIn);
                                        values.put(key, value);
                                    }
                                    
                                    Row row = new Row(rowId, values);
                                    table.getData().put(rowId, row);
                                }
                            }
                        }
                        
                        dataIn.close();
                        logger.info("Data loaded from file");
                    }
                }
                
                in.close();
                logger.info("Schemas and data loaded from {}", dbPath);
            }
        } catch (Exception e) {
            logger.warn("Error loading existing data, starting with empty DB: {}", e.getMessage(), e);
        } finally {
            dbLock.readLock().unlock();
        }
    }
    
    private Object readValue(DataInputStream in) throws IOException {
        boolean hasValue = in.readBoolean();
        if (!hasValue) {
            return null;
        }
        
        // Leer tipo y valor
        byte typeFlag = in.readByte();
        switch (typeFlag) {
            case 0: return in.readUTF();      // String
            case 1: return in.readInt();       // Integer
            case 2: return in.readLong();      // Long
            case 3: return in.readDouble();    // Double
            case 4: return in.readBoolean();   // Boolean
            default: return in.readUTF();      // Fallback a String
        }
    }

    private void saveToFile() throws IOException {
        // Use write lock for exclusive access during save
        dbLock.writeLock().lock();
        try {
            // First save all table data to the main file
            ByteArrayOutputStream dataBaos = new ByteArrayOutputStream();
            DataOutputStream dataOut = new DataOutputStream(dataBaos);
            
            // Save data from each table directly from ConcurrentHashMap to avoid OOM
            for (Map.Entry<String, Table> entry : tables.entrySet()) {
                Table table = entry.getValue();
                Map<Long, Row> tableData = getTableData(table);
                
                // Write table name
                dataOut.writeUTF(entry.getKey());
                dataOut.writeInt(tableData.size());
                
                // Write each row directly from internal map
                for (Map.Entry<Long, Row> rowEntry : tableData.entrySet()) {
                    Row row = rowEntry.getValue();
                    dataOut.writeLong(row.getRowId());
                    Map<String, Object> values = row.getValues();
                    dataOut.writeInt(values.size());
                    for (Map.Entry<String, Object> valEntry : values.entrySet()) {
                        dataOut.writeUTF(valEntry.getKey());
                        writeValue(dataOut, valEntry.getValue());
                    }
                }
            }
            dataOut.close();
            byte[] dataContent = dataBaos.toByteArray();
            
            // Now save schemas + concatenated data
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            
            // Save number of schemas
            out.writeInt(schemas.size());
            
            // Save each schema
            for (Map.Entry<String, TableSchema> entry : schemas.entrySet()) {
                TableSchema schema = entry.getValue();
                out.writeUTF(entry.getKey());
                
                // Save schema columns
                List<Column> columns = schema.getColumnsList();
                out.writeInt(columns.size());
                for (Column col : columns) {
                    out.writeUTF(col.getName());
                    out.writeUTF(col.getType().name());
                    out.writeBoolean(col.isPrimaryKey());
                    out.writeBoolean(col.isNotNull());
                }
            }
            
            // Save data length and content
            out.writeInt(dataContent.length);
            out.write(dataContent);
            
            out.close();
            byte[] content = baos.toByteArray();
            Files.write(dbPath, content);
            logger.debug("Database saved to {}", dbPath);
        } finally {
            dbLock.writeLock().unlock();
        }
    }
    
    private void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeBoolean(false);
            return;
        }
        
        out.writeBoolean(true);
        
        // Escribir tipo y valor
        if (value instanceof String) {
            out.writeByte(0);
            out.writeUTF((String) value);
        } else if (value instanceof Integer) {
            out.writeByte(1);
            out.writeInt((Integer) value);
        } else if (value instanceof Long) {
            out.writeByte(2);
            out.writeLong((Long) value);
        } else if (value instanceof Double) {
            out.writeByte(3);
            out.writeDouble((Double) value);
        } else if (value instanceof Boolean) {
            out.writeByte(4);
            out.writeBoolean((Boolean) value);
        } else {
            out.writeByte(0);
            out.writeUTF(value.toString());
        }
    }

    /**
     * Executes a native SQL-like query.
     * @param sql SQL query string
     * @param params Query parameters
     * @return List of rows matching the query
     * @throws Exception if query execution fails
     */
    public List<Row> executeQuery(String sql, Object... params) throws Exception {
        // Simple implementation - just delegate to table operations
        // This is a placeholder for a full SQL parser
        throw new UnsupportedOperationException("Native query execution not yet implemented. Use table() API instead.");
    }
}
