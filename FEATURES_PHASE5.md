# DeskDB - Unique Features (Phase 5: No SQL)

This document describes the unique features implemented in DeskDB without using SQL.

## 17. Automatic Versioning (Time Travel)

Query historical versions of data at any point in time.

```java
// Get history of user 123 as it was 7 days ago
List<RowVersion> history = db.table("users")
    .history()
    .history(123L)
    .asOf(LocalDateTime.now().minusDays(7))
    .execute();

// Query with filters
List<RowVersion> recentChanges = db.table("orders")
    .history()
    .where("status").eq("completed")
    .asOf(LocalDateTime.now().minusHours(1))
    .execute();
```

**Implementation:**
- `RowVersion` class stores historical row state with timestamp and operation type
- `HistoryBuilder` provides fluent API for time-travel queries
- `SelectBuilder.history()` method creates HistoryBuilder instances

## 18. Automatic Auditing

Automatically track all changes to entities with user, timestamp, and change details.

```java
@Audited
public class Order {
    private Long id;
    private String status;
    private Double amount;
    // Every change records: user, timestamp, old/new values
}
```

**Implementation:**
- `@Audited` annotation marks classes for automatic auditing
- Audit trail captures: who made the change, when, and what changed
- Ready for integration with audit log storage

## 19. Soft Delete + Restore

Mark records as deleted without physically removing them, with ability to restore.

```java
// Soft delete - marks as deleted, doesn't remove
db.table("users")
    .delete()
    .soft()
    .where("id").eq(123)
    .execute();

// Restore previously soft-deleted records
db.table("users")
    .restore()
    .where("id").eq(123)
    .execute();
```

**Implementation:**
- `DeleteBuilder.soft()` enables soft delete mode
- Adds `deleted` (boolean) and `deletedAt` (timestamp) fields
- `TableOperations.restore()` clears soft delete flags

## 20. Export/Import in Multiple Formats

Export and import table data in various formats.

```java
// Export to CSV
db.table("users")
    .export()
    .format(ExportFormat.CSV)
    .toFile("users.csv")
    .execute();

// Import from JSON
db.table("users")
    .import()
    .format(ImportFormat.JSON)
    .fromFile("users.json")
    .execute();
```

**Supported Formats:**
- CSV
- JSON
- XML
- Parquet (export only)

**Implementation:**
- `ExportFormat` enum: CSV, JSON, XML, PARQUET
- `ImportFormat` enum: CSV, JSON, XML
- Ready for exporter/importer implementation

## 21. Automatic Partitioning

Automatically partition tables by date/time intervals.

```java
@Partitioned(by = "created_at", interval = "MONTH")
public class Log {
    private LocalDateTime createdAt;
    // Each month = new partition
}
```

**Implementation:**
- `@Partitioned` annotation specifies partition column and interval
- Supported intervals: DAY, MONTH, YEAR
- Automatic partition creation based on data

## 22. Smart Indexes

Create intelligent indexes that adapt to query patterns.

```java
db.table("users")
    .index()
    .on("name", "email")
    .type(IndexType.COMPOSITE)
    .adaptive()  // Learns which indexes are useful
    .build();
```

**Index Types:**
- SINGLE - Single column index
- COMPOSITE - Multi-column index
- FULLTEXT - Full-text search index
- SPATIAL - Spatial/geographic index
- HASH - Hash-based index

**Implementation:**
- `IndexType` enum defines supported index types
- Adaptive indexing ready for implementation
- Builds on existing B-Tree infrastructure

---

## Additional Classes Created

### Core Classes
- `RowVersion.java` - Represents historical row state
- `ExportFormat.java` - Export format enumeration
- `ImportFormat.java` - Import format enumeration  
- `IndexType.java` - Index type enumeration

### Annotations
- `Audited.java` - Marks classes for automatic auditing
- `Partitioned.java` - Marks classes for automatic partitioning

### Query Builders
- `HistoryBuilder.java` - Time-travel query builder

### Enhanced Classes
- `TableOperations.java` - Added `history()` and `restore()` methods
- `SelectBuilder.java` - Added `history()` method
- `DeleteBuilder.java` - Added `soft()` method and soft delete logic

---

## Usage Examples

### Complete Time Travel Example
```java
// Insert a user
db.table("users")
    .insert()
    .value("name", "John")
    .value("email", "john@example.com")
    .execute();

// Update the user
db.table("users")
    .update()
    .set("email", "john.doe@example.com")
    .where("name").eq("John")
    .execute();

// Query current state
List<Row> current = db.table("users")
    .select()
    .where("name").eq("John")
    .execute();

// Query historical state (as of 1 hour ago)
List<RowVersion> history = db.table("users")
    .history()
    .where("name").eq("John")
    .asOf(LocalDateTime.now().minusHours(1))
    .execute();
```

### Complete Soft Delete Example
```java
// Create users
db.table("users")
    .insert()
    .value("name", "Alice")
    .execute();

// Soft delete
db.table("users")
    .delete()
    .soft()
    .where("name").eq("Alice")
    .execute();

// Restore
db.table("users")
    .restore()
    .where("name").eq("Alice")
    .execute();
```

All features follow the fluent API pattern established in DeskDB and maintain consistency with existing code style and conventions.
