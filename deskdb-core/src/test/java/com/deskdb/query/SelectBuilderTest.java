package com.deskdb.query;

import com.deskdb.core.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SelectBuilder class covering all query operations.
 */
@DisplayName("SelectBuilder Unit Tests")
public class SelectBuilderTest {
    
    private Path tempDbPath;
    private DeskDB db;
    
    @BeforeEach
    void setUp() throws Exception {
        tempDbPath = Files.createTempFile("select_test", ".deskdb");
        db = DeskDB.open(tempDbPath);
        
        // Create test table with schema
        db.createTable("users",
            new Column("id", DataType.LONG).primaryKey(),
            new Column("name", DataType.STRING),
            new Column("age", DataType.INT),
            new Column("salary", DataType.DOUBLE),
            new Column("active", DataType.BOOLEAN)
        );
        
        // Insert test data
        insertUser(1L, "Alice", 30, 50000.0, true);
        insertUser(2L, "Bob", 25, 45000.0, true);
        insertUser(3L, "Charlie", 35, 60000.0, false);
        insertUser(4L, "Diana", 28, 52000.0, true);
        insertUser(5L, "Eve", 32, 55000.0, true);
    }
    
    private void insertUser(Long id, String name, Integer age, Double salary, Boolean active) throws Exception {
        db.table("users").insert()
            .value("id", id)
            .value("name", name)
            .value("age", age)
            .value("salary", salary)
            .value("active", active)
            .execute();
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (tempDbPath != null && Files.exists(tempDbPath)) {
            Files.delete(tempDbPath);
        }
    }
    
    @Test
    @DisplayName("Test basic select all")
    void testBasicSelectAll() throws Exception {
        List<Row> results = db.table("users").select().execute();
        
        assertEquals(5, results.size());
    }
    
    @Test
    @DisplayName("Test select with specific columns")
    void testSelectWithColumns() throws Exception {
        List<Row> results = db.table("users").select()
            .columns("name", "age")
            .execute();
        
        assertEquals(5, results.size());
        assertTrue(results.get(0).getValues().containsKey("name"));
        assertTrue(results.get(0).getValues().containsKey("age"));
        assertFalse(results.get(0).getValues().containsKey("salary"));
    }
    
    @Test
    @DisplayName("Test select with equality filter using FilterBuilder")
    void testSelectWithEqualityFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("name").is("Alice")
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).get("name"));
    }
    
    @Test
    @DisplayName("Test select with greater than filter")
    void testSelectWithGreaterThanFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("age").greaterThan(30)
            .execute();
        
        assertEquals(2, results.size());
    }
    
    @Test
    @DisplayName("Test select with less than filter")
    void testSelectWithLessThanFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("age").lessThan(30)
            .execute();
        
        assertEquals(2, results.size());
    }
    
    @Test
    @DisplayName("Test select with greater than or equal filter")
    void testSelectWithGreaterThanOrEqualFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("age").greaterThanOrEqual(30)
            .execute();
        
        assertEquals(3, results.size());
    }
    
    @Test
    @DisplayName("Test select with less than or equal filter")
    void testSelectWithLessThanOrEqualFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("age").lessThanOrEqual(30)
            .execute();
        
        assertEquals(3, results.size());
    }
    
    @Test
    @DisplayName("Test select with between filter")
    void testSelectWithBetweenFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("age").between(28, 32)
            .execute();
        
        assertEquals(3, results.size());
    }
    
    @Test
    @DisplayName("Test select with not equal filter")
    void testSelectWithNotEqualFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("name").ne("Alice")
            .execute();
        
        assertEquals(4, results.size());
    }
    
    @Test
    @DisplayName("Test select with multiple filters using and")
    void testSelectWithMultipleFilters() throws Exception {
        List<Row> results = db.table("users").select()
            .where("active").is(true)
            .and("age").greaterThan(28)
            .execute();
        
        assertEquals(2, results.size());
    }
    
    @Test
    @DisplayName("Test select with limit")
    void testSelectWithLimit() throws Exception {
        List<Row> results = db.table("users").select()
            .limit(3)
            .execute();
        
        assertEquals(3, results.size());
    }
    
    @Test
    @DisplayName("Test select with offset")
    void testSelectWithOffset() throws Exception {
        List<Row> results = db.table("users").select()
            .offset(2)
            .execute();
        
        assertEquals(3, results.size());
    }
    
    @Test
    @DisplayName("Test select with limit and offset")
    void testSelectWithLimitAndOffset() throws Exception {
        List<Row> results = db.table("users").select()
            .offset(1)
            .limit(2)
            .execute();
        
        assertEquals(2, results.size());
    }
    
    @Test
    @DisplayName("Test select with order by ascending")
    void testSelectWithOrderByAsc() throws Exception {
        List<Row> results = db.table("users").select()
            .orderBy("age")
            .execute();
        
        assertEquals(5, results.size());
        assertEquals(25, results.get(0).get("age"));
        assertEquals(35, results.get(4).get("age"));
    }
    
    @Test
    @DisplayName("Test select with order by descending")
    void testSelectWithOrderByDesc() throws Exception {
        List<Row> results = db.table("users").select()
            .orderByDesc("age")
            .execute();
        
        assertEquals(5, results.size());
        assertEquals(35, results.get(0).get("age"));
        assertEquals(25, results.get(4).get("age"));
    }
    
    @Test
    @DisplayName("Test select with order by string column")
    void testSelectWithOrderByString() throws Exception {
        List<Row> results = db.table("users").select()
            .orderBy("name")
            .execute();
        
        assertEquals(5, results.size());
        assertEquals("Alice", results.get(0).get("name"));
    }
    
    @Test
    @DisplayName("Test select with complex query")
    void testSelectWithComplexQuery() throws Exception {
        List<Row> results = db.table("users").select()
            .columns("name", "salary")
            .where("active").is(true)
            .and("age").greaterThanOrEqual(28)
            .and("salary").greaterThan(49000.0)
            .orderByDesc("salary")
            .limit(2)
            .execute();
        
        assertTrue(results.size() <= 2);
        assertTrue(results.get(0).getValues().containsKey("name"));
        assertTrue(results.get(0).getValues().containsKey("salary"));
        assertFalse(results.get(0).getValues().containsKey("age"));
    }
    
    @Test
    @DisplayName("Test select with no results")
    void testSelectWithNoResults() throws Exception {
        List<Row> results = db.table("users").select()
            .where("age").greaterThan(100)
            .execute();
        
        assertTrue(results.isEmpty());
    }
    
    @Test
    @DisplayName("Test select with offset beyond result size")
    void testSelectWithOffsetBeyondSize() throws Exception {
        List<Row> results = db.table("users").select()
            .offset(100)
            .execute();
        
        assertTrue(results.isEmpty());
    }
    
    @Test
    @DisplayName("Test FilterBuilder methods")
    void testFilterBuilderMethods() throws Exception {
        List<Row> results1 = db.table("users").select()
            .where("name").eq("Bob")
            .execute();
        assertEquals(1, results1.size());
        
        List<Row> results2 = db.table("users").select()
            .where("age").gt(30)
            .execute();
        assertEquals(2, results2.size());
        
        List<Row> results3 = db.table("users").select()
            .where("age").gte(30)
            .execute();
        assertEquals(3, results3.size());
        
        List<Row> results4 = db.table("users").select()
            .where("age").lt(30)
            .execute();
        assertEquals(2, results4.size());
        
        List<Row> results5 = db.table("users").select()
            .where("age").lte(30)
            .execute();
        assertEquals(3, results5.size());
        
        List<Row> results6 = db.table("users").select()
            .where("name").isEqualTo("Charlie")
            .execute();
        assertEquals(1, results6.size());
    }
    
    @Test
    @DisplayName("Test WhereCondition methods")
    void testWhereConditionMethods() throws Exception {
        List<Row> results1 = db.table("users").select()
            .whereCond("name").eqCond("Diana")
            .andCond("name")
            .is("Diana")
            .execute();
        assertEquals(1, results1.size());
        
        List<Row> results2 = db.table("users").select()
            .whereCond("age").gtCond(30)
            .andCond("age")
            .greaterThan(30)
            .execute();
        assertEquals(2, results2.size());
        
        List<Row> results3 = db.table("users").select()
            .whereCond("name").neCond("Alice")
            .andCond("name")
            .ne("Alice")
            .execute();
        assertEquals(4, results3.size());
    }
    
    @Test
    @DisplayName("Test select with boolean filter")
    void testSelectWithBooleanFilter() throws Exception {
        List<Row> results = db.table("users").select()
            .where("active").is(false)
            .execute();
        
        assertEquals(1, results.size());
    }
    
    @Test
    @DisplayName("Test select with double comparison")
    void testSelectWithDoubleComparison() throws Exception {
        List<Row> results = db.table("users").select()
            .where("salary").greaterThan(50000.0)
            .execute();
        
        assertEquals(3, results.size());
    }
}
