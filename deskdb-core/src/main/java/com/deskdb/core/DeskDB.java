package com.deskdb.core;

import com.deskdb.index.BTree;
import com.deskdb.mapping.ObjectStore;
import com.deskdb.storage.Wal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Main entry point for DeskDB.
 * Manages database opening/closing and provides access to tables.
 * Acts as a Facade delegating to CatalogManager for metadata operations.
 * All content is saved in a single .deskdb file (like H2).
 */
public class DeskDB implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DeskDB.class);
    
    // ThreadLocal para rastrear transacciones activas y evitar anidamiento
    private static final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();

    private final Path dbPath;
    private final CatalogManager catalogManager; // Delegated metadata management
    private final ObjectStore objectStore; // Single shared instance for object persistence
    private Wal wal;
    private WriteConcern writeConcern = WriteConcern.NORMAL; // Default write concern
    private boolean closed = false;
    private final AtomicInteger transactionCounter = new AtomicInteger(0);
    private final ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock();
    private final boolean inMemoryOnly; // Flag to indicate if this is an in-memory only database

    public String getFilePath() {
        return dbPath.toString();
    }

    private DeskDB(Path dbPath) throws IOException {
        this.dbPath = dbPath;
        this.catalogManager = new CatalogManager(); // Initialize catalog manager
        this.inMemoryOnly = false;
        
        // Determinar ruta del WAL (mismo directorio que el archivo .deskdb)
        Path walPath = dbPath.resolveSibling(dbPath.getFileName().toString() + ".wal");
        
        // Initialize WAL FIRST before recovery to avoid NPE
        this.wal = Wal.open(walPath);
        
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
            // No guardar inmediatamente, el primer checkpoint o close lo hará
            // Esto evita errores cuando dbPath es un directorio temporal
        }
        
        // Initialize ObjectStore AFTER loading data to ensure proper order
        this.objectStore = new ObjectStore(this, false);
        this.objectStore.initialize();  // Explicit initialization after data load
        
        logger.info("DeskDB opened at {} with WAL at {}", dbPath.toAbsolutePath(), walPath.toAbsolutePath());
    }
    
    /**
     * Creates an in-memory only DeskDB instance.
     * All data (both SQL tables and objects) will be lost when the instance is closed or the JVM exits.
     * This is useful for testing or temporary data storage.
     * 
     * <p><strong>Performance Note:</strong> This implementation creates NO temporary files on the filesystem.
     * All data structures reside purely in RAM using ConcurrentHashMaps and HashMaps. This avoids:
     * <ul>
     *   <li>File I/O overhead for read/write operations</li>
     *   <li>Memory leaks from deleteOnExit() hooks in the JVM</li>
     *   <li>Disk space consumption for temporary data</li>
     * </ul>
     * </p>
     *
     * @return In-memory DeskDB instance
     * @throws IOException if there is an IO error (should never happen for in-memory mode)
     */
    public static DeskDB inMemory() throws IOException {
        // Use a symbolic path - no actual file operations occur when inMemoryOnly=true
        Path virtualPath = java.nio.file.Paths.get("memory://deskdb_" + System.nanoTime());
        
        DeskDB db = new DeskDB(virtualPath, true);
        logger.info("In-memory DeskDB created (zero disk I/O, pure RAM storage)");
        return db;
    }
    
    /**
     * Private constructor for in-memory mode.
     * Initializes all components without any filesystem interaction.
     * 
     * @param dbPath Virtual path (used only for identification, no file operations)
     * @param inMemoryOnly Flag to disable all persistence operations
     * @throws IOException if initialization fails
     */
    private DeskDB(Path dbPath, boolean inMemoryOnly) throws IOException {
        this.dbPath = dbPath;
        this.catalogManager = new CatalogManager();
        this.inMemoryOnly = inMemoryOnly;
        // Initialize ObjectStore with in-memory flag - no WAL or disk persistence
        this.objectStore = new ObjectStore(this, true);
        this.wal = null; // Explicitly null - no Write-Ahead Log for in-memory mode
        
        logger.info("In-memory DeskDB initialized (zero filesystem dependencies)");
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
        
        // Use write lock to ensure thread-safe table creation
        dbLock.writeLock().lock();
        try {
            if (catalogManager.hasTable(tableName)) {
                throw new IllegalStateException("La tabla '" + tableName + "' ya existe");
            }
            
            TableSchema schema = new TableSchema(tableName, List.of(columns));
            catalogManager.registerSchema(schema);
            
            Table table = new Table(tableName, List.of(columns), dbPath.toString());
            table.setDb(this);
            catalogManager.registerTable(table);
            catalogManager.registerIndex(tableName, new java.util.concurrent.ConcurrentHashMap<>());
            
            // Crear índice automático para primary key
            for (Column col : columns) {
                if (col.isPrimaryKey()) {
                    createIndex(tableName, col.getName() + "_idx", col.getName());
                }
            }
            
            logger.info("Tabla '{}' creada con {} columnas", tableName, columns.length);
            return table;
        } finally {
            dbLock.writeLock().unlock();
        }
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
        Table table = catalogManager.getTable(tableName);
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
            // Only save to file if not in-memory-only mode and dbPath is a regular file
            if (!inMemoryOnly && java.nio.file.Files.isRegularFile(dbPath)) {
                saveToFile();
            }
            
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
        
        Transaction tx = new Transaction(this, !autoCommit, writeConcern);
        if (!autoCommit) {
            currentTransaction.set(tx);
        }
        logger.debug("Started new transaction {} in thread {} (autoCommit={})", 
            transactionCounter.incrementAndGet(), Thread.currentThread().getName(), autoCommit);
        return tx;
    }
    
    /**
     * Sets the write concern level for durability vs performance trade-off.
     * 
     * @param writeConcern The write concern level (ASYNC, NORMAL, or SAFE)
     */
    public void setWriteConcern(WriteConcern writeConcern) {
        this.writeConcern = writeConcern;
        logger.info("Write concern set to {}", writeConcern);
    }
    
    /**
     * Gets the current write concern level.
     * 
     * @return The current write concern level
     */
    public WriteConcern getWriteConcern() {
        return writeConcern;
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
     * Obtiene el esquema de una tabla.
     */
    TableSchema getSchema(String tableName) {
        return catalogManager.getSchema(tableName);
    }

    /**
     * Registra un esquema de tabla.
     */
    void registerSchema(String tableName, TableSchema schema) {
        catalogManager.registerSchema(schema);
    }

    /**
     * Gets all tables from the catalog manager.
     * @return Map of table names to tables
     */
    Map<String, Table> getTables() {
        // For backward compatibility with saveToFile and other internal methods
        Map<String, Table> result = new java.util.HashMap<>();
        for (Table table : catalogManager.getAllTables()) {
            result.put(table.getName(), table);
        }
        return result;
    }

    /**
     * Gets table data for save operations.
     */
    Map<Long, Row> getTableData(Table table) {
        return table.data;
    }

    /**
     * Gets the index map for a table.
     */
    Map<String, BTree<?, ?>> getIndexMap(String tableName) {
        return catalogManager.getIndex(tableName);
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
        Map<String, BTree<?, ?>> tableIndexes = getIndexMap(tableName);
        if (tableIndexes == null) {
            tableIndexes = new java.util.concurrent.ConcurrentHashMap<>();
            // Note: We can't easily update the catalog here without more refactoring
            // For now, this is a limitation of the partial refactor
        }
        BTree<K, Long> btree = new BTree<>(indexName);
        tableIndexes.put(indexName, btree);
        
        // Index existing data
        Table table = catalogManager.getTable(tableName);
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
        Map<String, BTree<?, ?>> tableIndexes = getIndexMap(tableName);
        if (tableIndexes == null) {
            tableIndexes = new java.util.concurrent.ConcurrentHashMap<>();
        }
        
        // Use raw type to avoid generic bounds issues with composite keys
        @SuppressWarnings("rawtypes")
        BTree btree = new BTree(indexName);
        tableIndexes.put(indexName, btree);
        
        // Index existing data
        Table table = catalogManager.getTable(tableName);
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
        Map<String, BTree<?, ?>> tableIndexes = getIndexMap(tableName);
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
        // Use write lock since we're modifying data structures during load
        dbLock.writeLock().lock();
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
                        // Read remaining metadata for full atomic deserialization
                        boolean unique = in.available() > 0 && in.readBoolean();
                        Object defaultValue = null;
                        boolean hasDefault = in.available() > 0 && in.readBoolean();
                        if (hasDefault) {
                            // Read default value using BinarySerializer logic
                            // For backward compatibility, we skip complex default values
                            // Only primitive types and String are supported as defaults
                            byte typeCode = in.readByte();
                            if (typeCode >= 0) { // Not NULL
                                switch (typeCode) {
                                    case 0: // STRING
                                        defaultValue = in.readUTF();
                                        break;
                                    case 1: // INTEGER
                                        defaultValue = in.readInt();
                                        break;
                                    case 2: // LONG
                                        defaultValue = in.readLong();
                                        break;
                                    case 3: // DOUBLE
                                        defaultValue = in.readDouble();
                                        break;
                                    case 4: // BOOLEAN
                                        defaultValue = in.readBoolean();
                                        break;
                                    default:
                                        // Skip unsupported default value types for backward compatibility
                                        logger.warn(\"Skipping unsupported default value type: {}\", typeCode);
                                        break;
                                }
                            }
                        }
                        // Use atomic deserialization method to ensure immutability
                        columns[j] = Column.deserialize(colName, dataType, primaryKey, notNull, unique, defaultValue);
                    }
                    TableSchema schema = new TableSchema(tableName, List.of(columns));
                    catalogManager.registerSchema(schema);
                    
                    // Create table
                    Table table = new Table(tableName, List.of(columns), dbPath.toString());
                    table.setDb(this);
                    catalogManager.registerTable(table);
                    catalogManager.registerIndex(tableName, new java.util.concurrent.ConcurrentHashMap<>());
                }
                
                // Read data from each table directly (matches write format)
                while (in.available() >= 4) { // At least need 4 bytes for table name length
                    String tableName = in.readUTF();
                    int rowCount = in.readInt();
                    logger.debug("Loading {} rows from table {}", rowCount, tableName);
                    
                    Table table = catalogManager.getTable(tableName);
                    if (table != null) {
                        for (int i = 0; i < rowCount; i++) {
                            long rowId = in.readLong();
                            int valueCount = in.readInt();
                            Map<String, Object> values = new java.util.HashMap<>();
                            
                            for (int j = 0; j < valueCount; j++) {
                                String key = in.readUTF();
                                Object value = readValue(in);
                                values.put(key, value);
                                logger.trace("  {} = {} [type={}]", key, value, value != null ? value.getClass().getSimpleName() : "null");
                            }
                            
                            Row row = new Row(rowId, values);
                            table.getData().put(rowId, row);
                            logger.debug("  Loaded row {} with {} values", rowId, valueCount);
                        }
                    } else {
                        logger.warn("Table {} not found in catalog, skipping {} rows", tableName, rowCount);
                    }
                }
                
                logger.info("Data loaded from file: {} tables processed", catalogManager.getAllTables().size());
                
                in.close();
                logger.info("Schemas and data loaded from {}", dbPath);
            }
        } catch (Exception e) {
            logger.warn("Error loading existing data, starting with empty DB: {}", e.getMessage(), e);
        } finally {
            dbLock.writeLock().unlock();
        }
    }
    
    private Object readValue(DataInputStream in) throws IOException {
        // Read type code (matches writeValue format)
        byte typeCode = in.readByte();
        
        // Handle NULL marker (-1)
        if (typeCode == -1) {
            return null;
        }
        
        // Handle DataType enum ordinals
        DataType dataType = DataType.values()[typeCode];
        switch (dataType) {
            case STRING:
            case JSON:
                return in.readUTF();
            case INT:
                return in.readInt();
            case LONG:
                return in.readLong();
            case DOUBLE:
                return in.readDouble();
            case BOOLEAN:
                return in.readBoolean();
            case DECIMAL:
                String bdStr = in.readUTF();
                return new java.math.BigDecimal(bdStr);
            case DATE:
                return new java.util.Date(in.readLong());
            case TIMESTAMP:
                long tsMillis = in.readLong();
                int nanos = in.readInt();
                java.sql.Timestamp ts = new java.sql.Timestamp(tsMillis);
                ts.setNanos(nanos);
                return ts;
            case BLOB:
                int blobLen = in.readInt();
                byte[] blobData = new byte[blobLen];
                in.readFully(blobData);
                return blobData;
            default:
                throw new IOException("Unknown or unsupported data type during load: " + dataType);
        }
    }

    /**
     * Saves the database to disk using atomic write for crash safety.
     * Streams data directly to file to avoid OutOfMemoryError on large databases.
     * Public for ObjectStore access.
     * @throws IOException if there is an IO error
     */
    public void saveToFile() throws IOException {
        // Don't save if in-memory-only mode
        if (inMemoryOnly) {
            logger.debug("Skipping save for in-memory database");
            return;
        }
        
        // Use write lock for exclusive access during save
        dbLock.writeLock().lock();
        try {
            // ATOMIC WRITE: Stream directly to temp file (no ByteArrayOutputStream to avoid OOM)
            Path tempPath = dbPath.resolveSibling(dbPath.getFileName().toString() + ".tmp");
            
            try (DataOutputStream out = new DataOutputStream(
                    Files.newOutputStream(tempPath))) {
                
                // Save number of schemas
                out.writeInt(catalogManager.getAllSchemas().size());
                
                // Save each schema
                for (TableSchema schema : catalogManager.getAllSchemas()) {
                    out.writeUTF(schema.getName());
                    
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
                
                // Save data from each table directly to file (streaming, no intermediate buffer)
                for (Table table : catalogManager.getAllTables()) {
                    Map<Long, Row> tableData = getTableData(table);
                    
                    // Write table marker
                    out.writeUTF(table.getName());
                    out.writeInt(tableData.size());
                    
                    // Write each row directly from internal map
                    for (Map.Entry<Long, Row> rowEntry : tableData.entrySet()) {
                        Row row = rowEntry.getValue();
                        out.writeLong(row.getRowId());
                        Map<String, Object> values = row.getValues();
                        out.writeInt(values.size());
                        for (Map.Entry<String, Object> valEntry : values.entrySet()) {
                            out.writeUTF(valEntry.getKey());
                            writeValue(out, valEntry.getValue());
                        }
                    }
                }
            }
            
            // Atomically replace the original file with the temp file
            Files.move(tempPath, dbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, 
                      java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            
            logger.debug("Database saved atomically to {}", dbPath);
        } catch (IOException e) {
            // Clean up temp file if it exists
            Path tempPath = dbPath.resolveSibling(dbPath.getFileName().toString() + ".tmp");
            if (Files.exists(tempPath)) {
                try {
                    Files.delete(tempPath);
                } catch (IOException ex) {
                    logger.warn("Failed to delete temp file after error", ex);
                }
            }
            throw e;
        } finally {
            dbLock.writeLock().unlock();
        }
    }
    
    private void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            // Write NULL marker (-1)
            out.writeByte(-1);
            return;
        }
        
        // Write DataType enum ordinal followed by value
        if (value instanceof Boolean) {
            out.writeByte(DataType.BOOLEAN.ordinal());
            out.writeBoolean((Boolean) value);
        } else if (value instanceof Integer) {
            out.writeByte(DataType.INT.ordinal());
            out.writeInt((Integer) value);
        } else if (value instanceof Long) {
            out.writeByte(DataType.LONG.ordinal());
            out.writeLong((Long) value);
        } else if (value instanceof Double) {
            out.writeByte(DataType.DOUBLE.ordinal());
            out.writeDouble((Double) value);
        } else if (value instanceof String) {
            out.writeByte(DataType.STRING.ordinal());
            out.writeUTF((String) value);
        } else if (value instanceof java.math.BigDecimal) {
            out.writeByte(DataType.DECIMAL.ordinal());
            out.writeUTF(((java.math.BigDecimal) value).toPlainString());
        } else if (value instanceof java.util.Date) {
            out.writeByte(DataType.DATE.ordinal());
            out.writeLong(((java.util.Date) value).getTime());
        } else if (value instanceof java.sql.Timestamp) {
            out.writeByte(DataType.TIMESTAMP.ordinal());
            out.writeLong(((java.sql.Timestamp) value).getTime());
            out.writeInt(((java.sql.Timestamp) value).getNanos());
        } else if (value instanceof byte[]) {
            out.writeByte(DataType.BLOB.ordinal());
            byte[] blobData = (byte[]) value;
            out.writeInt(blobData.length);
            out.write(blobData);
        } else {
            // Fallback: serialize as JSON string
            out.writeByte(DataType.JSON.ordinal());
            out.writeUTF(value.toString());
        }
    }

}
