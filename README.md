# DeskDB

High-performance embedded columnar database for Java with ACID transactions, advanced query engine, and ORM support.

## Features

### Core Capabilities
- **Fluid API**: Intuitive chainable interface for CRUD operations
- **Columnar Storage**: Optimized for analytical queries and partial reads
- **Single File Persistence**: Portable `.deskdb` format with checksums
- **Type-Safe**: Strong typing with 9 supported data types
- **Zero External Dependencies**: Pure Java (only SLF4J for logging)

### Advanced Query Engine
- **Complex Filters**: Support for AND/OR logical operators with nested conditions
- **Comparison Operators**: EQ, NEQ, GT, LT, GTE, LTE, BETWEEN
- **ORDER BY**: Ascending and descending sorting on any column
- **LIMIT/OFFSET**: Pagination support for large result sets
- **Column Selection**: Project specific columns in queries

### ACID Transactions & Durability
- **Write-Ahead Log (WAL)**: Real WAL implementation ensuring durability before applying changes
- **Crash Recovery**: Automatic replay of committed transactions on startup
- **Transaction Isolation**: MVCC (Multi-Version Concurrency Control) with snapshot isolation
- **Granular Locking**: ReentrantReadWriteLock for fine-grained table-level concurrency
- **Atomic Operations**: All-or-nothing transaction semantics

### Indexing & Performance
- **B-Tree Indexes**: Automatic indexing on primary keys
- **Manual Index Creation**: Create indexes on any column for faster lookups
- **Query Optimization**: Query plan generation and optimization
- **Page Management**: Efficient memory-mapped file I/O with configurable page sizes

### ORM & Mapping
- **Entity Annotations**: JPA-style annotations (@Entity, @Table, @Id, @Column)
- **Relationships**: OneToOne, OneToMany, ManyToOne, ManyToMany
- **Cascade Operations**: Automatic cascade persist, merge, remove
- **Lifecycle Callbacks**: @PrePersist, @PostPersist, @PreUpdate, @PostUpdate, @PreRemove, @PostRemove
- **Field Validation**: @NotNull, @Size, @Min, @Max annotations
- **EntityManager**: Full-featured entity manager with automatic schema generation

### JDBC Compatibility
- **JDBC Driver**: Standard JDBC interface (Connection, Statement, PreparedStatement, ResultSet)
- **Connection Pooling**: Built-in connection pool for high-concurrency scenarios
- **Database Metadata**: Full DatabaseMetaData implementation

## Installation

```bash
mvn clean install
```

## Basic Usage

### Creating Tables

DeskDB uses schema-on-write, meaning tables are created automatically when you insert the first record. However, you can explicitly define table structure:

```java
// Open/create a database
DeskDB db = DeskDB.open("/path/to/my.deskdb");

// Table is created automatically on first insert
db.table("users")
  .insert()
  .value("name", "Ana")
  .value("age", 30)
  .value("email", "ana@example.com")
  .execute();

// Or use ORM with entity annotations (recommended for structured data)
@Entity
@Table(name = "products")
public class Product {
    @Id
    @Column(name = "id")
    private Long id;
    
    @Column(name = "name", nullable = false)
    private String name;
    
    @Column(name = "price")
    private Double price;
    
    // getters and setters
}

// EntityManager will create tables automatically
EntityManager em = db.createEntityManager();
em.getTransaction().begin();
em.persist(new Product(1L, "Laptop", 999.99));
em.getTransaction().commit();
```

#### Explicit Table Creation

You can also explicitly create tables with defined schemas using the `createTable()` method:

```java
// Create a table with explicit schema definition
db.createTable("employees",
    new Column("id", DataType.INTEGER).setPrimaryKey(true),
    new Column("name", DataType.STRING).setNotNull(true),
    new Column("salary", DataType.DOUBLE),
    new Column("hire_date", DataType.LOCAL_DATE)
);

// Now you can insert data into the explicitly created table
db.table("employees")
  .insert()
  .value("id", 1)
  .value("name", "John Doe")
  .value("salary", 75000.0)
  .value("hire_date", LocalDate.now())
  .execute();
```

This approach gives you full control over column types, primary keys, and constraints before inserting any data.

### CRUD Operations

```java
// Insert data
db.table("users")
  .insert()
  .value("name", "Ana")
  .value("age", 30)
  .value("email", "ana@example.com")
  .execute();

// Query with complex filters
List<Map<String, Object>> results = db.table("users")
  .select()
  .columns("name", "email")
  .where("age")
  .greaterThanOrEqual(18)
  .orderBy("name")
  .limit(10)
  .offset(0)
  .execute();

// Complex AND/OR conditions
Filter adultFilter = new Filter("age", Filter.Operator.GTE, 18);
Filter activeFilter = new Filter("status", Filter.Operator.EQ, "active");
Filter combinedFilter = adultFilter.and(activeFilter);

List<Map<String, Object>> activeAdults = db.table("users")
  .select()
  .addFilter(combinedFilter)
  .execute();

// Update data
db.table("users")
  .update()
  .set("age", 31)
  .where("name")
  .equals("Ana")
  .execute();

// Delete data
db.table("users")
  .delete()
  .where("name")
  .equals("Ana")
  .execute();

// Transaction with ACID guarantees
try (Transaction tx = db.beginTransaction()) {
    db.table("accounts").table(tx).insert().value("id", 1).value("balance", 1000).execute();
    db.table("accounts").table(tx).insert().value("id", 2).value("balance", 500).execute();
    tx.commit(); // Atomic commit with WAL
}

// Close the database
db.close();
```

## Supported Data Types

| Type | Description | Size |
|------|-------------|------|
| STRING | UTF-8 text | Variable |
| INT | 4-byte integer | 4 bytes |
| LONG | 8-byte integer | 8 bytes |
| DOUBLE | 8-byte floating point | 8 bytes |
| BOOLEAN | Boolean value | 1 byte |
| DATE | Date (epoch milliseconds) | 8 bytes |
| TIMESTAMP | Timestamp with nanosecond precision | 8 bytes |
| BLOB | Binary data | Variable |
| JSON | JSON text | Variable |

## Architecture Highlights

### Columnar Storage
Data is stored by columns rather than rows, enabling:
- Efficient partial reads (only read needed columns)
- Better compression ratios (similar data types together)
- Faster analytical queries (aggregations on single columns)

### Write-Ahead Logging (WAL)
All modifications are first written to the WAL before being applied to the main data file:
1. Operation logged to WAL with checksum
2. WAL forced to disk (fsync)
3. Operation applied to in-memory structures
4. Periodic checkpoint truncates WAL

This ensures durability even in case of power failure.

### Crash Recovery
On startup, DeskDB automatically:
1. Loads last saved state from `.deskdb` file
2. Reads WAL entries
3. Identifies committed but not persisted transactions
4. Replays committed operations
5. Discards uncommitted operations

### MVCC Concurrency
- Readers never block writers
- Writers never block readers
- Each transaction sees a consistent snapshot
- Granular locking at table level

## Roadmap

- [x] v0.1: Basic CRUD with persistence
- [x] v0.2: ACID transactions + Real WAL
- [x] v0.3: B-Tree indexes + Advanced filters (AND/OR)
- [x] v0.4: Query engine (ORDER BY, LIMIT, OFFSET)
- [x] v0.5: MVCC + Granular locking
- [x] v0.6: Full ORM with relationships
- [x] v0.7: JDBC driver + Connection pooling
- [ ] v0.8: Column compression (RLE, Delta encoding)
- [ ] v0.9: Server mode with network protocol
- [ ] v1.0: SQL parser + full SQL support
- [ ] v1.1: Multi-model (JSON + Relational hybrid)
- [ ] v2.0: Distributed mode with replication

## Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AdvancedQueryTest

# Run with coverage
mvn clean test jacoco:report
```

## Performance Considerations

- **Batch Inserts**: Use transactions for bulk inserts (100x faster)
- **Indexing**: Create indexes on frequently queried columns
- **Column Selection**: Only select needed columns to reduce I/O
- **Pagination**: Use LIMIT/OFFSET for large result sets
- **Connection Pooling**: Reuse connections in high-concurrency apps

## Examples

See the `src/test/java` directory for comprehensive examples:
- `DeskDBTest.java`: Basic CRUD operations
- `TransactionTest.java`: ACID transactions and recovery
- `AdvancedQueryTest.java`: Complex queries with AND/OR, ORDER BY, LIMIT
- `BTreeTest.java`: Index usage
- `EntityManagerTest.java`: ORM with entities
- `ManyToManyTest.java`: Relationship mapping
- `JDBCTest.java`: JDBC driver usage

## License

MIT License

## Contributing

Contributions are welcome! Please follow these guidelines:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes using conventional commits
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Commit Message Format

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

Types include:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

Example:
```
feat(query): add support for OR conditions in filters

Implemented LogicalOperator.OR in Filter class to enable
complex query conditions with OR logic.

Closes #42
```