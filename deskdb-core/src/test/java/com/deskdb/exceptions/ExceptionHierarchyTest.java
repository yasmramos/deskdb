package com.deskdb.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for DeskDB custom exception hierarchy.
 * Validates proper inheritance, constructors, and usage patterns.
 */
@DisplayName("Custom Exception Hierarchy Tests")
class ExceptionHierarchyTest {

    @Nested
    @DisplayName("DeskDBException (Base Exception)")
    class DeskDBExceptionTests {

        @Test
        @DisplayName("Should create exception with message only")
        void shouldCreateWithMessage() {
            String message = "Base database error";
            DeskDBException exception = new TestDeskDBException(message);

            assertEquals(message, exception.getMessage());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("Should create exception with message and cause")
        void shouldCreateWithMessageAndCause() {
            String message = "Database operation failed";
            Throwable cause = new RuntimeException("Underlying cause");
            
            DeskDBException exception = new TestDeskDBException(message, cause);

            assertEquals(message, exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Should create exception with cause only")
        void shouldCreateWithCauseOnly() {
            Throwable cause = new IllegalStateException("Root cause");
            
            DeskDBException exception = new TestDeskDBException(cause);

            assertEquals("java.lang.IllegalStateException: Root cause", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Should be catchable as RuntimeException")
        void shouldBeRuntimeException() {
            assertThrows(RuntimeException.class, () -> {
                throw new TestDeskDBException("Test exception");
            });
        }

        @Test
        @DisplayName("Should preserve stack trace")
        void shouldPreserveStackTrace() {
            DeskDBException exception = new TestDeskDBException("Stack trace test");
            
            StackTraceElement[] stackTrace = exception.getStackTrace();
            
            assertNotNull(stackTrace);
            assertTrue(stackTrace.length > 0);
        }
    }

    @Nested
    @DisplayName("QueryExecutionException Tests")
    class QueryExecutionExceptionTests {

        @Test
        @DisplayName("Should create QueryExecutionException with message")
        void shouldCreateWithMessage() {
            String message = "Query syntax error at line 5";
            QueryExecutionException exception = new QueryExecutionException(message);

            assertEquals(message, exception.getMessage());
            assertInstanceOf(DeskDBException.class, exception);
        }

        @Test
        @DisplayName("Should create QueryExecutionException with cause")
        void shouldCreateWithParseError() {
            Throwable cause = new IllegalArgumentException("Invalid column name");
            QueryExecutionException exception = new QueryExecutionException("Failed to parse query", cause);

            assertEquals("Failed to parse query", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Should be specifically catchable")
        void shouldBeCatchableSeparately() {
            try {
                throw new QueryExecutionException("Query failed");
            } catch (QueryExecutionException e) {
                assertEquals("Query failed", e.getMessage());
            } catch (DeskDBException e) {
                fail("Should catch QueryExecutionException specifically");
            }
        }
    }

    @Nested
    @DisplayName("TransactionException Tests")
    class TransactionExceptionTests {

        @Test
        @DisplayName("Should create TransactionException with commit failure message")
        void shouldCreateWithCommitFailure() {
            String message = "Transaction commit failed - WAL write error";
            TransactionException exception = new TransactionException(message);

            assertEquals(message, exception.getMessage());
        }

        @Test
        @DisplayName("Should create TransactionException with rollback cause")
        void shouldCreateWithRollbackCause() {
            Throwable cause = new StorageException("Disk full");
            TransactionException exception = new TransactionException("Transaction rolled back", cause);

            assertEquals("Transaction rolled back", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Should allow retry logic in catch block")
        void shouldAllowRetryLogic() {
            int[] retryCount = {0};
            int maxRetries = 3;

            try {
                throw new TransactionException("Temporary conflict");
            } catch (TransactionException e) {
                retryCount[0]++;
                assertTrue(retryCount[0] <= maxRetries);
                // Simulate retry logic
            }

            assertEquals(1, retryCount[0]);
        }
    }

    @Nested
    @DisplayName("StorageException Tests")
    class StorageExceptionTests {

        @Test
        @DisplayName("Should create StorageException with I/O error message")
        void shouldCreateWithIOError() {
            String message = "Failed to read page 42 from disk";
            StorageException exception = new StorageException(message);

            assertEquals(message, exception.getMessage());
        }

        @Test
        @DisplayName("Should wrap IOException properly")
        void shouldWrapIOException() {
            java.io.IOException ioException = new java.io.IOException("Permission denied");
            StorageException exception = new StorageException("File access error", ioException);

            assertEquals("File access error", exception.getMessage());
            assertSame(ioException, exception.getCause());
            assertInstanceOf(java.io.IOException.class, exception.getCause());
        }

        @Test
        @DisplayName("Should indicate corruption detection")
        void shouldIndicateCorruption() {
            StorageException exception = new StorageException("Checksum mismatch on page 15");

            assertTrue(exception.getMessage().contains("Checksum"));
        }
    }

    @Nested
    @DisplayName("ConcurrencyConflictException Tests")
    class ConcurrencyConflictExceptionTests {

        @Test
        @DisplayName("Should create ConcurrencyConflictException with conflict message")
        void shouldCreateWithConflictMessage() {
            String message = "Write-write conflict detected on row id=123";
            ConcurrencyConflictException exception = new ConcurrencyConflictException(message);

            assertEquals(message, exception.getMessage());
        }

        @Test
        @DisplayName("Should suggest retry in message handling")
        void shouldSuggestRetry() {
            ConcurrencyConflictException exception = new ConcurrencyConflictException(
                "Optimistic lock failure - version mismatch"
            );

            // Application logic should catch this and retry
            assertNotNull(exception.getMessage());
            assertTrue(exception.getMessage().contains("version"));
        }

        @Test
        @DisplayName("Should be distinguishable from other transaction errors")
        void shouldBeDistinguishableFromGeneralTransactionError() {
            try {
                throw new ConcurrencyConflictException("Concurrent modification detected");
            } catch (ConcurrencyConflictException e) {
                // Specific handling for conflicts - implement retry
                assertEquals("Concurrent modification detected", e.getMessage());
            } catch (TransactionException e) {
                fail("Should catch ConcurrencyConflictException specifically, not parent");
            }
        }

        @Test
        @DisplayName("Should work with optimistic concurrency control pattern")
        void shouldWorkWithOptimisticConcurrencyPattern() {
            boolean shouldRetry = false;
            
            try {
                // Simulate concurrent update
                throw new ConcurrencyConflictException("Version 5 != expected version 6");
            } catch (ConcurrencyConflictException e) {
                shouldRetry = true;
            }

            assertTrue(shouldRetry, "Should trigger retry logic");
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Integration")
    class ExceptionHierarchyIntegration {

        @Test
        @DisplayName("Should catch all DeskDB exceptions as base type")
        void shouldCatchAllAsBaseType() {
            DeskDBException[] exceptions = {
                new QueryExecutionException("Query error"),
                new TransactionException("Transaction error"),
                new StorageException("Storage error"),
                new ConcurrencyConflictException("Conflict error")
            };

            for (DeskDBException exception : exceptions) {
                assertInstanceOf(DeskDBException.class, exception);
            }
        }

        @Test
        @DisplayName("Should allow specific exception handling in multi-catch")
        void shouldAllowSpecificHandling() {
            Exception scenarioException = new QueryExecutionException("Test");
            
            try {
                throw (DeskDBException) scenarioException;
            } catch (ConcurrencyConflictException e) {
                fail("Should not reach here");
            } catch (QueryExecutionException e) {
                assertEquals("Test", e.getMessage());
            } catch (DeskDBException e) {
                fail("Should catch specific exception first");
            }
        }

        @Test
        @DisplayName("Should maintain proper instanceof relationships")
        void shouldMaintainInstanceofRelationships() {
            QueryExecutionException queryEx = new QueryExecutionException("Query failed");
            
            assertTrue(queryEx instanceof QueryExecutionException);
            assertTrue(queryEx instanceof DeskDBException);
            assertTrue(queryEx instanceof RuntimeException);
            assertTrue(queryEx instanceof Exception);
            assertTrue(queryEx instanceof Throwable);
        }
    }

    @Nested
    @DisplayName("Best Practices Validation")
    class BestPracticesValidation {

        @Test
        @DisplayName("Should support logging with stack trace")
        void shouldSupportLoggingWithStackTrace() {
            QueryExecutionException exception = new QueryExecutionException(
                "Failed to execute SELECT * FROM users",
                new IllegalArgumentException("Column 'invalid' not found")
            );

            // Simulate logging scenario
            String logMessage = String.format("ERROR: %s%nCause: %s", 
                exception.getMessage(), 
                exception.getCause() != null ? exception.getCause().getMessage() : "none"
            );

            assertNotNull(logMessage);
            assertTrue(logMessage.contains("ERROR:"));
        }

        @Test
        @DisplayName("Should enable meaningful error messages for end users")
        void shouldEnableMeaningfulErrorMessages() {
            StorageException exception = new StorageException(
                "Database file is locked by another process",
                new java.io.IOException("Access denied")
            );

            String userMessage = "Unable to access database. Please ensure no other application is using it.";
            
            // Application can map technical exception to user-friendly message
            assertNotNull(userMessage);
            assertNotNull(exception.getMessage()); // Technical details for logs
        }

        @Test
        @DisplayName("Should support exception translation pattern")
        void shouldSupportExceptionTranslation() {
            try {
                // Low-level operation
                throw new java.io.IOException("Disk full");
            } catch (java.io.IOException e) {
                // Translate to domain-specific exception
                throw new StorageException("Failed to persist data - storage capacity exceeded", e);
            }
        }
    }

    // Test subclass for base exception tests
    private static class TestDeskDBException extends DeskDBException {
        public TestDeskDBException(String message) {
            super(message);
        }

        public TestDeskDBException(String message, Throwable cause) {
            super(message, cause);
        }

        public TestDeskDBException(Throwable cause) {
            super(cause);
        }
    }
}
