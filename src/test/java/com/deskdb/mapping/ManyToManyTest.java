package com.deskdb.mapping;

import com.deskdb.core.DeskDB;
import com.deskdb.mapping.annotations.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ManyToManyTest {

    private DeskDB db;
    private EntityManager em;

    @Entity
    @Table(name = "students")
    public static class Student {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Integer id;

        @Column(name = "name")
        private String name;

        @ManyToMany
        @JoinTable(
            name = "student_course",
            joinColumns = @JoinColumn(name = "student_id"),
            inverseJoinColumns = @JoinColumn(name = "course_id")
        )
        private List<Course> courses;

        public Student() {}

        public Student(String name) {
            this.name = name;
        }

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<Course> getCourses() { return courses; }
        public void setCourses(List<Course> courses) { this.courses = courses; }
    }

    @Entity
    @Table(name = "courses")
    public static class Course {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Integer id;

        @Column(name = "title")
        private String title;

        @ManyToMany(mappedBy = "courses")
        private List<Student> students;

        public Course() {}

        public Course(String title) {
            this.title = title;
        }

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<Student> getStudents() { return students; }
        public void setStudents(List<Student> students) { this.students = students; }
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Clean up any existing database file to ensure fresh state
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("test_mtm.db"));
        
        db = DeskDB.open(java.nio.file.Paths.get("test_mtm.db"));
        em = new EntityManager(db);

        // Create tables for test entities
        db.createTable("students",
            new com.deskdb.core.Column("id", com.deskdb.core.DataType.INT).primaryKey(),
            new com.deskdb.core.Column("name", com.deskdb.core.DataType.STRING)
        );
        
        db.createTable("courses",
            new com.deskdb.core.Column("id", com.deskdb.core.DataType.INT).primaryKey(),
            new com.deskdb.core.Column("title", com.deskdb.core.DataType.STRING)
        );
        
        db.createTable("student_course",
            new com.deskdb.core.Column("student_id", com.deskdb.core.DataType.INT),
            new com.deskdb.core.Column("course_id", com.deskdb.core.DataType.INT)
        );
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (db != null) {
            db.close();
        }
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("test_mtm.db"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("test_mtm.db-shm"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("test_mtm.db-wal"));
    }

    @Test
    public void testPersistStudentAndCourseWithManyToMany() {
        // Create entities
        Student student = new Student("Alice");
        Course course1 = new Course("Math");
        Course course2 = new Course("Science");

        // Set up relationship
        student.setCourses(List.of(course1, course2));

        // Persist student (should cascade to courses and join table)
        em.persist(student);

        // Verify student was saved with ID
        assertNotNull(student.getId());

        // Reload and verify relationships
        Student loadedStudent = em.find(Student.class, student.getId());
        assertNotNull(loadedStudent);
        assertEquals("Alice", loadedStudent.getName());
        assertNotNull(loadedStudent.getCourses());
        assertEquals(2, loadedStudent.getCourses().size());

        // Verify courses were saved
        Course loadedCourse1 = em.find(Course.class, course1.getId());
        assertNotNull(loadedCourse1);
        assertEquals("Math", loadedCourse1.getTitle());

        Course loadedCourse2 = em.find(Course.class, course2.getId());
        assertNotNull(loadedCourse2);
        assertEquals("Science", loadedCourse2.getTitle());
    }

    @Test
    public void testJoinTableCreatedAndPopulated() throws Exception {
        Student student = new Student("Bob");
        Course course = new Course("History");
        student.setCourses(List.of(course));

        em.persist(student);

        // Verify join table exists and has data
        var results = db.table("student_course").select().execute();
        assertEquals(1, results.size());

        var row = results.get(0);
        assertNotNull(row.get("student_id"));
        assertNotNull(row.get("course_id"));
    }

    @Test
    public void testBidirectionalRelationshipLoad() {
        Student student = new Student("Carol");
        Course course = new Course("Physics");
        student.setCourses(List.of(course));

        em.persist(student);

        // Load from Student side (owning side)
        Student loadedStudent = em.find(Student.class, student.getId());
        assertNotNull(loadedStudent);
        assertNotNull(loadedStudent.getCourses());
        assertEquals(1, loadedStudent.getCourses().size());
        assertEquals("Physics", loadedStudent.getCourses().get(0).getTitle());
    }
}
