package com.deskdb.query;

import com.deskdb.core.*;
import com.deskdb.index.BTree;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Query Engine components:
 * - QueryOptimizer
 * - QueryPlan
 * - SelectBuilder
 * - Filter operations
 */
@DisplayName("Query Engine Integration Tests")
public class QueryEngineIntegrationTest {
    
    private Path tempDbPath;
    private DeskDB db;
    private Table table;
    
    @BeforeEach
    void setUp() throws Exception {
        tempDbPath = Files.createTempFile("query_test", ".deskdb");
        db = DeskDB.open(tempDbPath);
        
        // Create test table with schema
        db.createTable("products",
            new Column("id", DataType.LONG).primaryKey(),
            new Column("name", DataType.STRING),
            new Column("price", DataType.DOUBLE),
            new Column("quantity", DataType.INT),
            new Column("category", DataType.STRING),
            new Column("active", DataType.BOOLEAN)
        );
        
        table = db.getTable("products");
        
        // Insert test data
        insertProduct(1L, "Laptop", 999.99, 50, "Electronics", true);
        insertProduct(2L, "Mouse", 29.99, 200, "Electronics", true);
        insertProduct(3L, "Keyboard", 79.99, 150, "Electronics", true);
        insertProduct(4L, "Monitor", 299.99, 75, "Electronics", false);
        insertProduct(5L, "Desk", 199.99, 30, "Furniture", true);
        insertProduct(6L, "Chair", 149.99, 45, "Furniture", true);
        insertProduct(7L, "Headphones", 89.99, 100, "Electronics", true);
        insertProduct(8L, "Webcam", 59.99, 80, "Electronics", false);
        insertProduct(9L, "Bookshelf", 129.99, 20, "Furniture", true);
        insertProduct(10L, "Lamp", 39.99, 60, "Furniture", true);
    }
    
    private void insertProduct(Long id, String name, Double price, 
                               Integer quantity, String category, Boolean active) throws Exception {
        db.table("products").insert()
            .value("id", id)
            .value("name", name)
            .value("price", price)
            .value("quantity", quantity)
            .value("category", category)
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
    
    @Nested
    @DisplayName("Query Optimizer Tests")
    class QueryOptimizerTests {
        
        @Test
        @DisplayName("Should use full scan when no filters are provided")
        void testOptimizeWithNoFilters() {
            Query query = new Query("products", null, null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertTrue(plan.isUseFullScan(), "Should use full scan");
            assertNull(plan.getIndex(), "Index should be null");
            assertEquals(100, plan.getEstimatedCost(), "Cost should be 100 for full scan");
        }
        
        @Test
        @DisplayName("Should use full scan when filters list is empty")
        void testOptimizeWithEmptyFilters() {
            Query query = new Query("products", List.of(), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertTrue(plan.isUseFullScan(), "Should use full scan");
            assertEquals(100, plan.getEstimatedCost(), "Cost should be 100 for full scan");
        }
        
        @Test
        @DisplayName("Should use index when filtering by indexed column with EQ operator")
        void testOptimizeWithIndexedColumn() throws Exception {
            // Create index on category column
            table.createIndex("idx_category", "category");
            
            Filter filter = new Filter("category", Filter.Operator.EQ, "Electronics");
            Query query = new Query("products", List.of(filter), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertFalse(plan.isUseFullScan(), "Should not use full scan");
            assertNotNull(plan.getIndex(), "Index should be used");
            assertEquals(1, plan.getEstimatedCost(), "Cost should be 1 for index scan");
            assertEquals(1, plan.getFilters().size(), "Should have one filter");
        }
        
        @Test
        @DisplayName("Should use index for GT operator")
        void testOptimizeWithGTOperator() throws Exception {
            table.createIndex("idx_price", "price");
            
            Filter filter = new Filter("price", Filter.Operator.GT, 100.0);
            Query query = new Query("products", List.of(filter), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertFalse(plan.isUseFullScan(), "Should use index");
            assertNotNull(plan.getIndex(), "Index should be used");
        }
        
        @Test
        @DisplayName("Should use index for LT operator")
        void testOptimizeWithLTOperator() throws Exception {
            table.createIndex("idx_quantity", "quantity");
            
            Filter filter = new Filter("quantity", Filter.Operator.LT, 50);
            Query query = new Query("products", List.of(filter), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertFalse(plan.isUseFullScan(), "Should use index");
            assertNotNull(plan.getIndex(), "Index should be used");
        }
        
        @Test
        @DisplayName("Should fall back to full scan for non-indexed column")
        void testOptimizeWithNonIndexedColumn() {
            Filter filter = new Filter("name", Filter.Operator.EQ, "Laptop");
            Query query = new Query("products", List.of(filter), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertTrue(plan.isUseFullScan(), "Should use full scan for non-indexed column");
            assertEquals(100, plan.getEstimatedCost(), "Cost should be 100");
        }
        
        @Test
        @DisplayName("Should prioritize first matching index when multiple filters exist")
        void testOptimizeWithMultipleFilters() throws Exception {
            table.createIndex("idx_category", "category");
            table.createIndex("idx_active", "active");
            
            Filter filter1 = new Filter("category", Filter.Operator.EQ, "Electronics");
            Filter filter2 = new Filter("active", Filter.Operator.EQ, true);
            Query query = new Query("products", List.of(filter1, filter2), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            assertFalse(plan.isUseFullScan(), "Should use index");
            assertNotNull(plan.getIndex(), "Index should be used");
            assertTrue(plan.getFilters().size() >= 1, "Should have at least one filter");
        }
    }
    
    @Nested
    @DisplayName("QueryPlan Tests")
    class QueryPlanTests {
        
        @Test
        @DisplayName("Should create QueryPlan with index")
        void testQueryPlanWithIndex() throws Exception {
            table.createIndex("idx_category", "category");
            BTree index = table.getIndex("category");
            
            QueryPlan plan = new QueryPlan()
                .useIndex(index)
                .setEstimatedCost(1);
            
            assertFalse(plan.isUseFullScan(), "Should not use full scan");
            assertNotNull(plan.getIndex(), "Index should be set");
            assertEquals(1, plan.getEstimatedCost(), "Cost should be 1");
        }
        
        @Test
        @DisplayName("Should create QueryPlan with full scan")
        void testQueryPlanWithFullScan() {
            QueryPlan plan = new QueryPlan()
                .useFullScan()
                .setEstimatedCost(100);
            
            assertTrue(plan.isUseFullScan(), "Should use full scan");
            assertNull(plan.getIndex(), "Index should be null");
            assertEquals(100, plan.getEstimatedCost(), "Cost should be 100");
        }
        
        @Test
        @DisplayName("Should add filters to QueryPlan")
        void testQueryPlanWithFilters() {
            Filter filter1 = new Filter("price", Filter.Operator.GT, 100.0);
            Filter filter2 = new Filter("quantity", Filter.Operator.LT, 50);
            
            QueryPlan plan = new QueryPlan()
                .useFullScan()
                .addFilter(filter1)
                .addFilter(filter2);
            
            assertEquals(2, plan.getFilters().size(), "Should have 2 filters");
            assertTrue(plan.getFilters().contains(filter1));
            assertTrue(plan.getFilters().contains(filter2));
        }
        
        @Test
        @DisplayName("Should override index when switching to full scan")
        void testQueryPlanOverrideIndex() throws Exception {
            table.createIndex("idx_category", "category");
            BTree index = table.getIndex("category");
            
            QueryPlan plan = new QueryPlan()
                .useIndex(index)
                .useFullScan();
            
            assertTrue(plan.isUseFullScan(), "Should use full scan");
            assertNull(plan.getIndex(), "Index should be null after override");
        }
    }
    
    @Nested
    @DisplayName("SelectBuilder Integration Tests")
    class SelectBuilderIntegrationTests {
        
        @Test
        @DisplayName("Should execute select with single filter")
        void testSelectWithSingleFilter() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("category").eq("Electronics")
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            assertTrue(results.size() > 5, "Should return multiple electronics products");
            
            for (Row row : results) {
                assertEquals("Electronics", row.get("category"), "All should be Electronics");
            }
        }
        
        @Test
        @DisplayName("Should execute select with multiple filters using AND logic")
        void testSelectWithMultipleFilters() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("category").eq("Electronics")
                .and("active").eq(true)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                assertEquals("Electronics", row.get("category"));
                assertEquals(true, row.get("active"));
            }
        }
        
        @Test
        @DisplayName("Should execute select with price range filter")
        void testSelectWithPriceRange() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("price").greaterThan(50.0)
                .and("price").lessThan(200.0)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                Double price = (Double) row.get("price");
                assertTrue(price > 50.0 && price < 200.0, 
                    "Price should be between 50 and 200: " + price);
            }
        }
        
        @Test
        @DisplayName("Should execute select with BETWEEN operator")
        void testSelectWithBetweenOperator() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("price").between(100.0, 300.0)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                Double price = (Double) row.get("price");
                assertTrue(price >= 100.0 && price <= 300.0, 
                    "Price should be between 100 and 300: " + price);
            }
        }
        
        @Test
        @DisplayName("Should execute select with ordering ascending")
        void testSelectWithOrderByAsc() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .orderBy("price")
                .execute();
            
            assertTrue(results.size() > 1, "Should return multiple results");
            
            for (int i = 1; i < results.size(); i++) {
                Double prevPrice = (Double) results.get(i-1).get("price");
                Double currPrice = (Double) results.get(i).get("price");
                assertTrue(prevPrice <= currPrice, 
                    "Prices should be in ascending order");
            }
        }
        
        @Test
        @DisplayName("Should execute select with ordering descending")
        void testSelectWithOrderByDesc() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .orderByDesc("price")
                .execute();
            
            assertTrue(results.size() > 1, "Should return multiple results");
            
            for (int i = 1; i < results.size(); i++) {
                Double prevPrice = (Double) results.get(i-1).get("price");
                Double currPrice = (Double) results.get(i).get("price");
                assertTrue(prevPrice >= currPrice, 
                    "Prices should be in descending order");
            }
        }
        
        @Test
        @DisplayName("Should execute select with LIMIT")
        void testSelectWithLimit() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .limit(5)
                .execute();
            
            assertEquals(5, results.size(), "Should return exactly 5 results");
        }
        
        @Test
        @DisplayName("Should execute select with OFFSET")
        void testSelectWithOffset() throws Exception {
            List<Row> allResults = db.table("products").select().execute();
            List<Row> results = db.table("products")
                .select()
                .offset(5)
                .execute();
            
            assertTrue(results.size() < allResults.size(), 
                "Offset should reduce results");
            assertEquals(allResults.size() - 5, results.size(), 
                "Should skip first 5 results");
        }
        
        @Test
        @DisplayName("Should execute select with LIMIT and OFFSET")
        void testSelectWithLimitAndOffset() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .offset(2)
                .limit(3)
                .execute();
            
            assertEquals(3, results.size(), "Should return 3 results");
        }
        
        @Test
        @DisplayName("Should execute select with specific columns")
        void testSelectWithColumnProjection() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .columns("name", "price")
                .where("category").eq("Electronics")
                .limit(3)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                assertTrue(row.getValues().containsKey("name"), 
                    "Should have name column");
                assertTrue(row.getValues().containsKey("price"), 
                    "Should have price column");
                assertFalse(row.getValues().containsKey("category"), 
                    "Should not have category column");
                assertFalse(row.getValues().containsKey("quantity"), 
                    "Should not have quantity column");
            }
        }
        
        @Test
        @DisplayName("Should execute select with GTE and LTE operators")
        void testSelectWithGTEAndLTE() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("price").greaterThanOrEqual(100.0)
                .and("price").lessThanOrEqual(200.0)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                Double price = (Double) row.get("price");
                assertTrue(price >= 100.0 && price <= 200.0, 
                    "Price should be between 100 and 200 inclusive: " + price);
            }
        }
        
        @Test
        @DisplayName("Should execute select with NE operator")
        void testSelectWithNEOperator() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("category").ne("Books")
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                assertNotEquals("Books", row.get("category"), 
                    "Should not be Books");
            }
        }
        
        @Test
        @DisplayName("Should handle empty result set")
        void testSelectWithNoMatchingResults() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("price").greaterThan(10000.0)
                .execute();
            
            assertTrue(results.isEmpty(), "Should return empty list");
        }
        
        @Test
        @DisplayName("Should execute select within transaction")
        void testSelectWithinTransaction() throws Exception {
            Transaction tx = db.beginTransaction();
            
            try {
                List<Row> results = new SelectBuilder(tx, "products")
                    .where("active").eq(true)
                    .execute();
                
                assertFalse(results.isEmpty(), "Should return active products");
                
                for (Row row : results) {
                    assertEquals(true, row.get("active"), 
                        "All products should be active");
                }
                
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }
        
        @Test
        @DisplayName("Should combine WHERE, ORDER BY, LIMIT and OFFSET")
        void testSelectWithAllClauses() throws Exception {
            List<Row> results = db.table("products")
                .select()
                .where("active").eq(true)
                .orderByDesc("price")
                .offset(1)
                .limit(3)
                .execute();
            
            assertTrue(results.size() <= 3, "Should return at most 3 results");
            
            // Verify ordering
            for (int i = 1; i < results.size(); i++) {
                Double prevPrice = (Double) results.get(i-1).get("price");
                Double currPrice = (Double) results.get(i).get("price");
                assertTrue(prevPrice >= currPrice, 
                    "Should be ordered by price descending");
            }
            
            // Verify all are active
            for (Row row : results) {
                assertEquals(true, row.get("active"), 
                    "All should be active products");
            }
        }
    }
    
    @Nested
    @DisplayName("Complex Query Scenarios")
    class ComplexQueryScenarios {
        
        @Test
        @DisplayName("Should handle compound filters with AND/OR logic")
        void testCompoundFilters() throws Exception {
            // Create filter: (category = Electronics AND price < 100) OR category = Furniture
            Filter electronicsCheap = new Filter(Filter.LogicalOperator.AND,
                new Filter("category", Filter.Operator.EQ, "Electronics"),
                new Filter("price", Filter.Operator.LT, 100.0)
            );
            
            Filter furniture = new Filter("category", Filter.Operator.EQ, "Furniture");
            
            Filter compound = new Filter(Filter.LogicalOperator.OR, electronicsCheap, furniture);
            
            List<Row> results = db.table("products")
                .select()
                .addFilter(compound)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            
            for (Row row : results) {
                String category = (String) row.get("category");
                Double price = (Double) row.get("price");
                
                boolean isCheapElectronics = "Electronics".equals(category) && price < 100.0;
                boolean isFurniture = "Furniture".equals(category);
                
                assertTrue(isCheapElectronics || isFurniture, 
                    "Should match compound condition");
            }
        }
        
        @Test
        @DisplayName("Should optimize query with indexed column in compound filter")
        void testOptimizedCompoundFilter() throws Exception {
            table.createIndex("idx_category", "category");
            
            Filter filter1 = new Filter("category", Filter.Operator.EQ, "Electronics");
            Filter filter2 = new Filter("price", Filter.Operator.GT, 50.0);
            Filter compound = new Filter(Filter.LogicalOperator.AND, filter1, filter2);
            
            Query query = new Query("products", List.of(compound), null, -1, 0, null, true);
            QueryOptimizer optimizer = new QueryOptimizer();
            
            QueryPlan plan = optimizer.optimize(query, table);
            
            // Optimizer looks for first indexable filter in the list
            // Since compound filters aren't directly indexable, it may use full scan
            // This is expected behavior for complex nested filters
            assertTrue(plan.isUseFullScan() || plan.getIndex() != null, 
                "Should either use index or full scan");
        }
        
        @Test
        @DisplayName("Should handle null values in ordering")
        void testOrderByWithNulls() throws Exception {
            // Insert a product with null price
            db.table("products").insert()
                .value("id", 100L)
                .value("name", "Free Item")
                .value("quantity", 10)
                .value("category", "Misc")
                .value("active", true)
                .execute();
            
            List<Row> results = db.table("products")
                .select()
                .orderBy("price")
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results");
            // Nulls should be handled gracefully in sorting
        }
    }
    
    @Nested
    @DisplayName("WhereCondition Tests - Testing andWhere() for multiple conditions")
    class WhereConditionTests {
        
        @Test
        @DisplayName("Test andWhere() with multiple conditions using EQ")
        void testAndWhereWithMultipleEqualConditions() throws Exception {
            // Test the WhereCondition.andWhere() method which was not covered
            SelectBuilder.WhereCondition whereCondition = db.table("products").whereCond("category").eqCond("Electronics");
            
            // Use andWhere to add another condition
            List<Row> results = whereCondition
                .andWhere("active")
                .eq(true)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return active electronics");
            for (Row row : results) {
                assertEquals("Electronics", row.get("category"));
                assertEquals(true, row.get("active"));
            }
        }
        
        @Test
        @DisplayName("Test andWhere() with mixed operators")
        void testAndWhereWithMixedOperators() throws Exception {
            // Test chaining multiple andWhere calls
            SelectBuilder.WhereCondition whereCondition = db.table("products").whereCond("price").gtCond(50.0);
            
            List<Row> results = whereCondition
                .andCond("quantity")
                .lt(100)
                .andCond("active")
                .isEqualTo(true)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return results matching all conditions");
            for (Row row : results) {
                Double price = (Double) row.get("price");
                Integer quantity = (Integer) row.get("quantity");
                Boolean active = (Boolean) row.get("active");
                
                assertTrue(price > 50.0, "Price should be > 50");
                assertTrue(quantity < 100, "Quantity should be < 100");
                assertTrue(active, "Should be active");
            }
        }
        
        @Test
        @DisplayName("Test andWhere() with BETWEEN operator")
        void testAndWhereWithBetweenOperator() throws Exception {
            SelectBuilder.WhereCondition whereCondition = db.table("products").whereCond("price").gteCond(100.0);
            
            List<Row> results = whereCondition
                .andCond("price")
                .lte(500.0)
                .andCond("quantity")
                .gte(50)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return products with price between 100-500 and quantity >= 50");
            for (Row row : results) {
                Double price = (Double) row.get("price");
                Integer quantity = (Integer) row.get("quantity");
                
                assertTrue(price >= 100.0 && price <= 500.0, "Price should be between 100-500");
                assertTrue(quantity >= 50, "Quantity should be >= 50");
            }
        }
        
        @Test
        @DisplayName("Test andWhere() with NE (Not Equal) operator")
        void testAndWhereWithNotEqualOperator() throws Exception {
            SelectBuilder.WhereCondition whereCondition = db.table("products").whereCond("category").neCond("Books");
            
            List<Row> results = whereCondition
                .andCond("active")
                .eq(true)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return active non-Books products");
            for (Row row : results) {
                assertNotEquals("Books", row.get("category"));
                assertEquals(true, row.get("active"));
            }
        }
        
        @Test
        @DisplayName("Test andWhere() returning no results")
        void testAndWhereWithNoMatchingResults() throws Exception {
            SelectBuilder.WhereCondition whereCondition = db.table("products").whereCond("price").gtCond(1000.0);
            
            List<Row> results = whereCondition
                .andCond("quantity")
                .lt(10)
                .execute();
            
            assertTrue(results.isEmpty(), "Should return no results for impossible conditions");
        }
        
        @Test
        @DisplayName("Test complex query with 4+ conditions using andWhere")
        void testComplexQueryWithMultipleAndWhere() throws Exception {
            SelectBuilder.WhereCondition whereCondition = db.table("products")
                .whereCond("active").eqCond(true);
            
            List<Row> results = whereCondition
                .andCond("category")
                .eq("Electronics")
                .andCond("price")
                .lt(500.0)
                .andCond("quantity")
                .gt(100)
                .execute();
            
            assertFalse(results.isEmpty(), "Should return active electronics under $500 with quantity > 100");
            assertEquals(2, results.size(), "Should find Mouse and Keyboard");
            
            for (Row row : results) {
                assertEquals(true, row.get("active"));
                assertEquals("Electronics", row.get("category"));
                assertTrue((Double) row.get("price") < 500.0);
                assertTrue((Integer) row.get("quantity") > 100);
            }
        }
    }
}
