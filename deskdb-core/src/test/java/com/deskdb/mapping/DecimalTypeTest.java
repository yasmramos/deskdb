package com.deskdb.mapping;

import com.deskdb.core.DataType;
import com.deskdb.core.DeskDB;
import com.deskdb.mapping.annotations.Column;
import com.deskdb.mapping.annotations.Entity;
import com.deskdb.mapping.annotations.Id;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BigDecimal (DECIMAL) type support in ORM.
 */
public class DecimalTypeTest {

    private Path dbPath;
    private DeskDB db;
    private EntityManager entityManager;

    @Entity(name = "products")
    public static class Product {
        @Id
        @Column(name = "id", type = DataType.LONG)
        private Long id;

        @Column(name = "name", type = DataType.STRING)
        private String name;

        @Column(name = "price", type = DataType.DECIMAL)
        private BigDecimal price;

        @Column(name = "stock", type = DataType.INT)
        private Integer stock;

        public Product() {}

        public Product(String name, BigDecimal price, Integer stock) {
            this.name = name;
            this.price = price;
            this.stock = stock;
        }

        // Getters and Setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
    }

    @BeforeEach
    public void setUp() throws IOException {
        dbPath = Files.createTempDirectory("deskdb_decimal_test").resolve("test.decimal.db");
        db = DeskDB.open(dbPath.toString());
        entityManager = db.createEntityManager();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (dbPath != null && Files.exists(dbPath)) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    public void testPersistAndRetrieveProductWithDecimalPrice() {
        Product product = new Product("Laptop", new BigDecimal("999.99"), 50);
        
        entityManager.persist(product);
        
        assertNotNull(product.getId());
        
        List<Product> products = entityManager.query(Product.class, "name = ?", "Laptop");
        assertEquals(1, products.size());
        
        Product retrieved = products.get(0);
        assertEquals("Laptop", retrieved.getName());
        assertEquals(new BigDecimal("999.99"), retrieved.getPrice());
        assertEquals(50, retrieved.getStock());
    }

    @Test
    public void testBigDecimalPrecisionInORM() {
        Product product = new Product("Premium Watch", new BigDecimal("123456789.123456789012345678"), 10);
        
        entityManager.persist(product);
        
        List<Product> products = entityManager.query(Product.class, "name = ?", "Premium Watch");
        assertEquals(1, products.size());
        
        Product retrieved = products.get(0);
        BigDecimal retrievedPrice = retrieved.getPrice();
        
        assertEquals(new BigDecimal("123456789.123456789012345678"), retrievedPrice);
        // Note: scale and precision may vary depending on serialization format
        assertTrue(retrievedPrice.scale() >= 18, "Scale should be at least 18");
        assertTrue(retrievedPrice.precision() >= 21, "Precision should be at least 21");
    }

    @Test
    public void testNegativeDecimalValue() {
        Product product = new Product("Refunded Item", new BigDecimal("-50.00"), 0);
        
        entityManager.persist(product);
        
        List<Product> products = entityManager.query(Product.class, "name = ?", "Refunded Item");
        assertEquals(1, products.size());
        
        Product retrieved = products.get(0);
        assertEquals(new BigDecimal("-50.00"), retrieved.getPrice());
    }

    @Test
    public void testZeroDecimalValue() {
        Product product = new Product("Free Sample", new BigDecimal("0.00"), 100);
        
        entityManager.persist(product);
        
        List<Product> products = entityManager.query(Product.class, "name = ?", "Free Sample");
        assertEquals(1, products.size());
        
        Product retrieved = products.get(0);
        assertEquals(new BigDecimal("0.00"), retrieved.getPrice());
    }

    @Test
    public void testMultipleProductsDifferentDecimalValues() {
        Product p1 = new Product("Cheap Item", new BigDecimal("9.99"), 1000);
        Product p2 = new Product("Medium Item", new BigDecimal("49.50"), 500);
        Product p3 = new Product("Expensive Item", new BigDecimal("9999.99"), 5);
        
        entityManager.persist(p1);
        entityManager.persist(p2);
        entityManager.persist(p3);
        
        List<Product> allProducts = entityManager.query(Product.class);
        assertEquals(3, allProducts.size());
        
        BigDecimal totalValue = allProducts.stream()
            .map(Product::getPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        assertEquals(new BigDecimal("10059.48"), totalValue);
    }

    @Test
    public void testUpdateDecimalValue() {
        Product product = new Product("Price Change Test", new BigDecimal("100.00"), 20);
        entityManager.persist(product);
        
        product.setPrice(new BigDecimal("75.50"));
        entityManager.update(product);
        
        List<Product> products = entityManager.query(Product.class, "name = ?", "Price Change Test");
        assertEquals(1, products.size());
        
        Product updated = products.get(0);
        assertEquals(new BigDecimal("75.50"), updated.getPrice());
    }
}
