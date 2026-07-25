package com.deskdb.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para EntityValidator.
 * Valida todas las anotaciones de validación y edge cases.
 */
@DisplayName("EntityValidator Integration Tests")
class EntityValidatorTest {

    // Clases de prueba para validación
    static class TestEntity {
        @NotNull(message = "Name cannot be null")
        private String name;

        @Size(min = 2, max = 50, message = "Name must be between 2 and 50 characters")
        private String description;

        @Min(value = 0, message = "Age must be at least {value}")
        private Integer age;

        @Max(value = 150, message = "Age cannot exceed {value}")
        private Integer maxAge;

        @NotNull(message = "Emails cannot be null")
        @Size(min = 1, max = 10, message = "Must have between 1 and 10 emails")
        private Collection<String> emails;

        public TestEntity() {
            this.emails = new ArrayList<>();
        }

        // Getters y setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        public Integer getMaxAge() { return maxAge; }
        public void setMaxAge(Integer maxAge) { this.maxAge = maxAge; }
        public Collection<String> getEmails() { return emails; }
        public void setEmails(Collection<String> emails) { this.emails = emails; }
    }

    @Test
    @DisplayName("should return empty errors for valid entity")
    void shouldReturnEmptyErrorsForValidEntity() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John Doe");
        entity.setDescription("A valid description");
        entity.setAge(25);
        entity.setMaxAge(100);
        entity.getEmails().add("john@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertTrue(errors.isEmpty(), "Valid entity should have no errors");
    }

    @Test
    @DisplayName("should detect null value in NotNull field")
    void shouldDetectNullInNotNullField() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName(null); // Violates @NotNull
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(50);
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Name cannot be null")), 
                   "Should report null name error");
    }

    @Test
    @DisplayName("should detect string too short for Size annotation")
    void shouldDetectStringTooShort() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("A"); // Too short (min 2)
        entity.setAge(30);
        entity.setMaxAge(50);
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("between 2 and 50")), 
                   "Should report size violation");
    }

    @Test
    @DisplayName("should detect string too long for Size annotation")
    void shouldDetectStringTooLong() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("A".repeat(51)); // Too long (max 50)
        entity.setAge(30);
        entity.setMaxAge(50);
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("between 2 and 50")), 
                   "Should report size violation");
    }

    @Test
    @DisplayName("should detect value below Min annotation")
    void shouldDetectValueBelowMin() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(-5); // Below min 0
        entity.setMaxAge(50);
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("at least")), 
                   "Should report min violation");
    }

    @Test
    @DisplayName("should detect value above Max annotation")
    void shouldDetectValueAboveMax() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(200); // Above max 150
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("cannot exceed")), 
                   "Should report max violation");
    }

    @Test
    @DisplayName("should detect null collection in NotNull field")
    void shouldDetectNullCollection() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(50);
        entity.setEmails(null); // Violates @NotNull

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Emails cannot be null")), 
                   "Should report null collection error");
    }

    @Test
    @DisplayName("should detect empty collection violating Size min")
    void shouldDetectEmptyCollectionViolatingSizeMin() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(50);
        entity.setEmails(new ArrayList<>()); // Empty, violates min 1

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("between 1 and 10")), 
                   "Should report collection size violation");
    }

    @Test
    @DisplayName("should detect collection too large violating Size max")
    void shouldDetectCollectionTooLarge() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(50);
        Collection<String> emails = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            emails.add("email" + i + "@example.com");
        }
        entity.setEmails(emails); // 15 emails, violates max 10

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("between 1 and 10")), 
                   "Should report collection size violation");
    }

    @Test
    @DisplayName("should accumulate multiple validation errors")
    void shouldAccumulateMultipleErrors() throws IllegalAccessException {
        // Given - Entity with multiple violations
        TestEntity entity = new TestEntity();
        entity.setName(null); // Violates @NotNull
        entity.setDescription("A"); // Too short
        entity.setAge(-10); // Below min
        entity.setMaxAge(999); // Above max
        entity.setEmails(null); // Violates @NotNull

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertTrue(errors.size() >= 4, "Should accumulate multiple errors");
    }

    @Test
    @DisplayName("should return error for null entity")
    void shouldReturnErrorForNullEntity() throws IllegalAccessException {
        // When
        List<String> errors = EntityValidator.validate(null);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation error");
        assertTrue(errors.contains("Entity cannot be null"), "Should report null entity error");
    }

    @Test
    @DisplayName("should validateAndThrow exception on invalid entity")
    void shouldValidateAndThrowOnInvalidEntity() {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName(null); // Invalid

        // When & Then
        assertThrows(ValidationException.class, () -> {
            EntityValidator.validateAndThrow(entity);
        }, "Should throw ValidationException for invalid entity");
    }

    @Test
    @DisplayName("should not throw exception for valid entity")
    void shouldNotThrowForValidEntity() throws Exception {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("Valid Name");
        entity.setDescription("Valid description");
        entity.setAge(25);
        entity.setMaxAge(100);
        entity.getEmails().add("valid@example.com");

        // When & Then - Should not throw
        assertDoesNotThrow(() -> {
            EntityValidator.validateAndThrow(entity);
        }, "Valid entity should not throw exception");
    }

    @Test
    @DisplayName("should handle boundary values correctly")
    void shouldHandleBoundaryValues() throws IllegalAccessException {
        // Given - Entity at exact boundaries
        TestEntity entity = new TestEntity();
        entity.setName("Jo"); // Exactly 2 chars (min)
        entity.setDescription("A".repeat(50)); // Exactly 50 chars (max)
        entity.setAge(0); // Exactly min
        entity.setMaxAge(150); // Exactly max
        entity.getEmails().add("one@email.com"); // Exactly 1 (min)

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertTrue(errors.isEmpty(), "Boundary values should be valid");
    }

    @Test
    @DisplayName("should handle entity without annotations")
    void shouldHandleEntityWithoutAnnotations() throws IllegalAccessException {
        // Given
        class PlainEntity {
            private String value;
            public String getValue() { return value; }
            public void setValue(String value) { this.value = value; }
        }
        PlainEntity entity = new PlainEntity();
        entity.setValue(null);

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertTrue(errors.isEmpty(), "Entity without annotations should always be valid");
    }

    @Test
    @DisplayName("should handle valid collection within Size bounds")
    void shouldHandleValidCollection() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(50);
        Collection<String> emails = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            emails.add("email" + i + "@example.com");
        }
        entity.setEmails(emails); // 5 emails (within 1-10 range)

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertTrue(errors.isEmpty(), "Valid collection should pass validation");
    }

    @Test
    @DisplayName("should handle zero value for Min annotation")
    void shouldHandleZeroValueForMin() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(0); // Exactly at min boundary
        entity.setMaxAge(50);
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertTrue(errors.isEmpty(), "Zero should be valid when min is 0");
    }

    @Test
    @DisplayName("should handle negative values correctly")
    void shouldHandleNegativeValues() throws IllegalAccessException {
        // Given
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(-1); // Below min 0
        entity.setMaxAge(-50); // Valid (below max 150)
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertFalse(errors.isEmpty(), "Should have error for age below min");
        assertTrue(errors.stream().anyMatch(e -> e.contains("at least")), 
                   "Should report min violation for negative age");
    }

    @Test
    @DisplayName("should validate all fields independently")
    void shouldValidateAllFieldsIndependently() throws IllegalAccessException {
        // Given - Only one field invalid
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setDescription("Valid");
        entity.setAge(30);
        entity.setMaxAge(200); // Only this is invalid
        entity.getEmails().add("test@example.com");

        // When
        List<String> errors = EntityValidator.validate(entity);

        // Then
        assertEquals(1, errors.size(), "Should have exactly one error");
        assertTrue(errors.get(0).contains("cannot exceed"), "Should report maxAge violation");
    }
}
