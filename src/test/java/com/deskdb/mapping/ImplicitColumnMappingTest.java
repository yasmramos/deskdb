package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.mapping.annotations.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that fields without @Column annotation are mapped implicitly by field name.
 */
public class ImplicitColumnMappingTest {

    private static DeskDB db;
    private static EntityManager em;
    private static Path dbPath;

    @Entity
    @Table(name = "implicit_test")
    static class Product {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // No @Column annotation - should map implicitly by field name
        private String name;

        // No @Column annotation - should map implicitly by field name
        private Double price;

        // No @Column annotation - should map implicitly by field name
        private Integer quantity;

        @Transient
        private String notPersisted;

        public Product() {}

        public Product(String name, Double price, Integer quantity) {
            this.name = name;
            this.price = price;
            this.quantity = quantity;
            this.notPersisted = "transient_value";
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public String getNotPersisted() { return notPersisted; }
        public void setNotPersisted(String notPersisted) { this.notPersisted = notPersisted; }
    }

    @BeforeEach
    public void setUp() throws Exception {
        dbPath = Files.createTempFile("deskdb_implicit_", ".deskdb");
        db = DeskDB.open(dbPath);
        em = new EntityManager(db);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (db != null) db.close();
        if (dbPath != null && Files.exists(dbPath)) {
            Files.delete(dbPath);
        }
    }

    @Test
    public void testPersistAndLoadWithoutColumnAnnotation() {
        // Create product with fields that don't have @Column annotation
        Product product = new Product("Laptop", 999.99, 10);

        // Persist the entity
        em.persist(product);

        // Verify ID was generated
        assertNotNull(product.getId());
        assertTrue(product.getId() > 0);

        // Load the entity back
        Product loaded = em.find(Product.class, product.getId());

        // Verify all fields were persisted and loaded correctly (even without @Column)
        assertNotNull(loaded);
        assertEquals("Laptop", loaded.getName());
        assertEquals(999.99, loaded.getPrice());
        assertEquals(10, loaded.getQuantity());

        // Verify transient field is NOT persisted
        assertNull(loaded.getNotPersisted());
    }

    @Test
    public void testUpdateWithoutColumnAnnotation() {
        Product product = new Product("Phone", 599.00, 25);
        em.persist(product);

        // Update the product
        product.setName("Smartphone");
        product.setPrice(699.00);
        product.setQuantity(30);

        em.persist(product);

        // Load and verify updates
        Product loaded = em.find(Product.class, product.getId());
        assertEquals("Smartphone", loaded.getName());
        assertEquals(699.00, loaded.getPrice());
        assertEquals(30, loaded.getQuantity());
    }

    @Test
    public void testFindAllWithoutColumnAnnotation() {
        em.persist(new Product("Product A", 10.0, 1));
        em.persist(new Product("Product B", 20.0, 2));
        em.persist(new Product("Product C", 30.0, 3));

        List<Product> products = em.findAll(Product.class);

        assertEquals(3, products.size());

        // Verify each product has correct data
        Product productA = products.stream()
            .filter(p -> p.getName().equals("Product A"))
            .findFirst()
            .orElse(null);

        assertNotNull(productA);
        assertEquals(10.0, productA.getPrice());
        assertEquals(1, productA.getQuantity());
    }

    @Test
    public void testMixedAnnotations() {
        // Entity with some fields having @Column and some without
        MixedEntity mixed = new MixedEntity();
        mixed.explicitField = "explicit";
        mixed.implicitField = "implicit";

        em.persist(mixed);

        MixedEntity loaded = em.find(MixedEntity.class, mixed.id);

        // Both fields should be loaded regardless of annotation
        assertEquals("explicit", loaded.explicitField);
        assertEquals("implicit", loaded.implicitField);
    }

    @Entity
    @Table(name = "mixed_test")
    static class MixedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "explicit_field")  // Explicit annotation
        private String explicitField;

        private String implicitField;  // No annotation - should map implicitly

        public MixedEntity() {}
    }
}
