package com.deskdb.mapping.test;

import com.deskdb.core.DeskDB;
import com.deskdb.mapping.EntityManager;
import com.deskdb.mapping.annotations.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for relationship mappings (@ManyToOne and @OneToMany).
 */
public class RelationshipMappingTest {

    @TempDir
    Path tempDir;

    private DeskDB db;
    private EntityManager em;

    @Entity
    @Table(name = "authors")
    public static class Author {
        @Id
        @Column(name = "id")
        public Long id;

        @Column(name = "name")
        public String name;

        public Author() {}

        public Author(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "books")
    public static class Book {
        @Id
        @Column(name = "id")
        public Long id;

        @Column(name = "title")
        public String title;

        @ManyToOne(targetEntity = Author.class, joinColumn = "author_id")
        public Author author;

        public Book() {}

        public Book(String title, Author author) {
            this.title = title;
            this.author = author;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        Path dbPath = tempDir.resolve("relationships.db");
        db = DeskDB.open(dbPath);
        em = new EntityManager(db);

        // Create tables using the proper API with DataType enum
        db.createTable("authors", 
            new com.deskdb.core.Column("id", com.deskdb.core.DataType.LONG),
            new com.deskdb.core.Column("name", com.deskdb.core.DataType.STRING)
        );
        
        db.createTable("books",
            new com.deskdb.core.Column("id", com.deskdb.core.DataType.LONG),
            new com.deskdb.core.Column("title", com.deskdb.core.DataType.STRING),
            new com.deskdb.core.Column("author_id", com.deskdb.core.DataType.LONG)
        );
    }

    @Test
    public void testPersistAndLoadWithManyToOne() throws Exception {
        // Create and persist author
        Author author = new Author("J.K. Rowling");
        em.persist(author);

        // Create and persist book with relationship
        Book book = new Book("Harry Potter", author);
        em.persist(book);

        // Load book and verify relationship
        Book loadedBook = em.find(Book.class, book.id);
        
        assertNotNull(loadedBook);
        assertEquals("Harry Potter", loadedBook.title);
        assertNotNull(loadedBook.author);
        assertEquals("J.K. Rowling", loadedBook.author.name);
    }

    @Test
    public void testMultipleBooksSameAuthor() {
        // Create author
        Author author = new Author("George Orwell");
        em.persist(author);

        // Create multiple books by same author
        Book book1 = new Book("1984", author);
        Book book2 = new Book("Animal Farm", author);
        em.persist(book1);
        em.persist(book2);

        // Load both books and verify author relationship
        Book loadedBook1 = em.find(Book.class, book1.id);
        Book loadedBook2 = em.find(Book.class, book2.id);

        assertNotNull(loadedBook1.author);
        assertNotNull(loadedBook2.author);
        assertEquals("George Orwell", loadedBook1.author.name);
        assertEquals("George Orwell", loadedBook2.author.name);
        assertEquals(loadedBook1.author.id, loadedBook2.author.id);
    }

    @Test
    public void testFindAllWithRelationships() {
        // Create authors
        Author author1 = new Author("Author One");
        Author author2 = new Author("Author Two");
        em.persist(author1);
        em.persist(author2);

        // Create books
        em.persist(new Book("Book A", author1));
        em.persist(new Book("Book B", author2));
        em.persist(new Book("Book C", author1));

        // Find all books
        List<Book> books = em.findAll(Book.class);
        assertEquals(3, books.size());

        // Verify relationships
        long booksByAuthor1 = books.stream()
                .filter(b -> b.author != null && b.author.name.equals("Author One"))
                .count();
        assertEquals(2, booksByAuthor1);

        long booksByAuthor2 = books.stream()
                .filter(b -> b.author != null && b.author.name.equals("Author Two"))
                .count();
        assertEquals(1, booksByAuthor2);
    }

    @Test
    public void testBookWithoutAuthor() {
        // Create book without setting author
        Book book = new Book("Standalone Book", null);
        em.persist(book);

        // Load book
        Book loadedBook = em.find(Book.class, book.id);
        assertNotNull(loadedBook);
        assertEquals("Standalone Book", loadedBook.title);
        assertNull(loadedBook.author);
    }
}
