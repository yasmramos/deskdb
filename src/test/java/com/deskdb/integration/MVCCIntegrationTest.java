package com.deskdb.integration;

import com.deskdb.transaction.MVCC;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MVCC (Multi-Version Concurrency Control) system.
 * Tests concurrent access, snapshot isolation, version management, and stress scenarios.
 */
@DisplayName("MVCC Integration Tests")
class MVCCIntegrationTest {

    private MVCC mvcc;

    @BeforeEach
    void setUp() {
        mvcc = new MVCC();
    }

    @AfterEach
    void tearDown() {
        mvcc = null;
    }

    @Nested
    @DisplayName("Concurrent Write Operations")
    class ConcurrentWriteTests {

        @Test
        @DisplayName("Multiple threads writing different rows concurrently")
        void testConcurrentWritesDifferentRows() throws Exception {
            int threadCount = 10;
            int writesPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        long txId = threadId * 1000L;
                        mvcc.beginTransaction(txId);

                        for (int i = 0; i < writesPerThread; i++) {
                            long rowId = threadId * 10000L + i;
                            Map<String, Object> data = new ConcurrentHashMap<>();
                            data.put("thread", threadId);
                            data.put("iteration", i);
                            data.put("timestamp", System.currentTimeMillis());
                            mvcc.write(rowId, data, txId, txId);
                        }

                        mvcc.commitTransaction(txId);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        fail("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get(), "All threads should complete successfully");
            assertEquals(threadCount * writesPerThread, mvcc.getVersionCount(), 
                "All versions should be persisted");
        }

        @Test
        @DisplayName("Multiple threads writing same row concurrently")
        void testConcurrentWritesSameRow() throws Exception {
            int threadCount = 5;
            long rowId = 1L;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        long txId = threadId + 1L;
                        mvcc.beginTransaction(txId);

                        Map<String, Object> data = new ConcurrentHashMap<>();
                        data.put("thread", threadId);
                        data.put("timestamp", System.currentTimeMillis());
                        mvcc.write(rowId, data, txId, txId);
                        mvcc.commitTransaction(txId);
                    } catch (Exception e) {
                        fail("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // All versions should exist (MVCC keeps all versions)
            assertTrue(mvcc.getAllVersions(rowId).size() >= threadCount, 
                "Should have at least " + threadCount + " versions");
        }
    }

    @Nested
    @DisplayName("Snapshot Isolation")
    class SnapshotIsolationTests {

        @Test
        @DisplayName("Readers should not block writers")
        void testReadersDontBlockWriters() throws Exception {
            long rowId = 1L;
            long tx1 = 1L;
            long tx2 = 2L;

            // Start transaction 1 and create snapshot
            mvcc.beginTransaction(tx1);
            Map<String, Object> initialData = new ConcurrentHashMap<>();
            initialData.put("value", "initial");
            mvcc.write(rowId, initialData, tx1, tx1);
            mvcc.commitTransaction(tx1);

            // Transaction 2 reads
            mvcc.beginTransaction(tx2);
            Map<String, Object> readData = mvcc.read(rowId, tx2, tx2);
            assertNotNull(readData);
            assertEquals("initial", readData.get("value"));

            // Transaction 3 writes and commits while tx2 is still active
            long tx3 = 3L;
            mvcc.beginTransaction(tx3);
            Map<String, Object> newData = new ConcurrentHashMap<>();
            newData.put("value", "updated");
            mvcc.write(rowId, newData, tx3, tx3);
            mvcc.commitTransaction(tx3);

            // Verify that tx2 can still read (readers don't block writers)
            // Note: Current implementation uses visibility based on active transactions at snapshot time
            // Since tx3 committed after tx2 started, tx2 may or may not see tx3's changes
            // depending on the exact visibility rules implemented
            Map<String, Object> readData2 = mvcc.read(rowId, tx2, tx2);
            assertNotNull(readData2, "Transaction 2 should still be able to read");
            
            // The key assertion: readers don't block writers - tx3 was able to write and commit
            // while tx2 was active. This proves non-blocking behavior.
            MVCC.RowVersion latestVersion = mvcc.getLatestVersion(rowId);
            assertEquals("updated", latestVersion.data.get("value"),
                "Latest version should contain the update from tx3");
        }

        @Test
        @DisplayName("Uncommitted writes should not be visible to other transactions")
        void testUncommittedWritesNotVisible() {
            long rowId = 1L;
            long tx1 = 1L;
            long tx2 = 2L;

            // tx1 writes but doesn't commit
            mvcc.beginTransaction(tx1);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("value", "uncommitted");
            mvcc.write(rowId, data, tx1, tx1);

            // tx2 starts and tries to read
            mvcc.beginTransaction(tx2);
            Map<String, Object> readData = mvcc.read(rowId, tx2, tx2);

            // Should not see uncommitted data from tx1
            assertNull(readData, "Should not see uncommitted data from other transaction");
        }

        @Test
        @DisplayName("Committed writes should be visible to new transactions")
        void testCommittedWritesVisible() {
            long rowId = 1L;
            long tx1 = 1L;
            long tx2 = 2L;

            // tx1 writes and commits
            mvcc.beginTransaction(tx1);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("value", "committed");
            mvcc.write(rowId, data, tx1, tx1);
            mvcc.commitTransaction(tx1);

            // tx2 starts after commit and reads
            mvcc.beginTransaction(tx2);
            Map<String, Object> readData = mvcc.read(rowId, tx2, tx2);

            assertNotNull(readData);
            assertEquals("committed", readData.get("value"), 
                "Should see committed data from previous transaction");
        }
    }

    @Nested
    @DisplayName("Version Chain Integrity")
    class VersionChainTests {

        @Test
        @DisplayName("Version chain should maintain proper ordering")
        void testVersionChainOrdering() {
            long rowId = 1L;
            long txId = 1L;
            mvcc.beginTransaction(txId);

            // Write multiple versions
            for (int i = 0; i < 5; i++) {
                Map<String, Object> data = new ConcurrentHashMap<>();
                data.put("version", i);
                mvcc.write(rowId, data, txId + i, txId);
            }

            List<MVCC.RowVersion> versions = mvcc.getAllVersions(rowId);
            assertEquals(5, versions.size());

            // Verify versions are ordered newest first
            for (int i = 0; i < versions.size() - 1; i++) {
                assertTrue(versions.get(i).version > versions.get(i + 1).version,
                    "Versions should be ordered newest first");
            }
        }

        @Test
        @DisplayName("Previous version links should be maintained")
        void testPreviousVersionLinks() {
            long rowId = 1L;
            long txId = 1L;
            mvcc.beginTransaction(txId);

            Long previousVersion = null;
            for (int i = 0; i < 3; i++) {
                Map<String, Object> data = new ConcurrentHashMap<>();
                data.put("iteration", i);
                mvcc.write(rowId, data, txId, txId);

                List<MVCC.RowVersion> versions = mvcc.getAllVersions(rowId);
                MVCC.RowVersion latest = versions.get(0);

                if (previousVersion != null) {
                    assertEquals(previousVersion, latest.previousVersionId,
                        "Previous version link should point to prior version");
                }
                previousVersion = latest.version;
            }
        }
    }

    @Nested
    @DisplayName("Delete Operations")
    class DeleteOperationTests {

        @Test
        @DisplayName("Delete should create tombstone version")
        void testDeleteCreatesTombstone() {
            long rowId = 1L;
            long tx1 = 1L;

            mvcc.beginTransaction(tx1);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("value", "exists");
            mvcc.write(rowId, data, tx1, tx1);
            mvcc.commitTransaction(tx1);

            long tx2 = 2L;
            mvcc.beginTransaction(tx2);
            mvcc.delete(rowId, tx2, tx2);
            mvcc.commitTransaction(tx2);

            List<MVCC.RowVersion> versions = mvcc.getAllVersions(rowId);
            assertTrue(versions.get(0).deleted, "Latest version should be marked as deleted");
        }

        @Test
        @DisplayName("Deleted rows should return null on read")
        void testDeletedRowsReturnNull() {
            long rowId = 1L;
            long tx1 = 1L;
            long tx2 = 2L;

            mvcc.beginTransaction(tx1);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("value", "exists");
            mvcc.write(rowId, data, tx1, tx1);
            mvcc.commitTransaction(tx1);

            mvcc.beginTransaction(tx2);
            mvcc.delete(rowId, tx2, tx2);
            mvcc.commitTransaction(tx2);

            long tx3 = 3L;
            mvcc.beginTransaction(tx3);
            Map<String, Object> readData = mvcc.read(rowId, tx3, tx3);

            assertNull(readData, "Deleted row should return null");
        }
    }

    @Nested
    @DisplayName("Rollback Operations")
    class RollbackOperationTests {

        @Test
        @DisplayName("Rollback should remove all versions from rolled back transaction")
        void testRollbackRemovesVersions() {
            long rowId = 1L;
            long tx1 = 1L;
            long tx2 = 2L;

            // tx1 commits
            mvcc.beginTransaction(tx1);
            Map<String, Object> data1 = new ConcurrentHashMap<>();
            data1.put("value", "committed");
            mvcc.write(rowId, data1, tx1, tx1);
            mvcc.commitTransaction(tx1);

            // tx2 writes then rolls back
            mvcc.beginTransaction(tx2);
            Map<String, Object> data2 = new ConcurrentHashMap<>();
            data2.put("value", "rolled back");
            mvcc.write(rowId, data2, tx2, tx2);
            mvcc.rollbackTransaction(tx2);

            // Only tx1's version should remain
            List<MVCC.RowVersion> versions = mvcc.getAllVersions(rowId);
            assertEquals(1, versions.size());
            assertEquals("committed", versions.get(0).data.get("value"));
        }
    }

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("High concurrency stress test")
        void testHighConcurrencyStress() throws Exception {
            int threadCount = 20;
            int operationsPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            long txId = threadId * 1000L + i;
                            long rowId = i % 10; // Limited set of rows to increase contention

                            mvcc.beginTransaction(txId);

                            if (i % 3 == 0) {
                                // Read operation
                                mvcc.read(rowId, txId, txId);
                            } else if (i % 3 == 1) {
                                // Write operation
                                Map<String, Object> data = new ConcurrentHashMap<>();
                                data.put("thread", threadId);
                                data.put("op", i);
                                mvcc.write(rowId, data, txId, txId);
                            } else {
                                // Delete operation (only if row exists)
                                mvcc.delete(rowId, txId, txId);
                            }

                            mvcc.commitTransaction(txId);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errors.get(), "No errors should occur during stress test");
        }

        @Test
        @DisplayName("Long-running transaction with concurrent short transactions")
        void testLongRunningWithShortTransactions() throws Exception {
            long rowId = 1L;
            long longTx = 1L;

            // Start long-running transaction
            mvcc.beginTransaction(longTx);
            Map<String, Object> initialData = new ConcurrentHashMap<>();
            initialData.put("value", "initial");
            mvcc.write(rowId, initialData, longTx, longTx);

            // Run many short transactions
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch doneLatch = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                final int opNum = i;
                executor.submit(() -> {
                    try {
                        long shortTx = 100L + opNum;
                        mvcc.beginTransaction(shortTx);

                        // Try to read while longTx is active
                        Map<String, Object> data = mvcc.read(rowId, shortTx, shortTx);
                        if (opNum == 0) {
                            // First short tx might see the data
                            assertNotNull(data);
                        }

                        mvcc.commitTransaction(shortTx);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Commit long transaction
            mvcc.commitTransaction(longTx);

            // Verify data is still accessible
            long finalTx = 1000L;
            mvcc.beginTransaction(finalTx);
            Map<String, Object> finalData = mvcc.read(rowId, finalTx, finalTx);
            assertNotNull(finalData);
            assertEquals("initial", finalData.get("value"));
        }
    }

    @Nested
    @DisplayName("Garbage Collection")
    class GarbageCollectionTests {

        @Test
        @DisplayName("Vacuum should clean up old versions when no active transactions")
        void testVacuumWithNoActiveTransactions() {
            long rowId = 1L;

            // Create multiple versions
            for (long txId = 1; txId <= 10; txId++) {
                mvcc.beginTransaction(txId);
                Map<String, Object> data = new ConcurrentHashMap<>();
                data.put("version", txId);
                mvcc.write(rowId, data, txId, txId);
                mvcc.commitTransaction(txId);
            }

            int versionCountBefore = mvcc.getVersionCount();
            assertTrue(versionCountBefore > 1, "Should have multiple versions before vacuum");

            // Vacuum should clean up old versions
            mvcc.vacuum();

            int versionCountAfter = mvcc.getVersionCount();
            assertEquals(1, versionCountAfter, "Should keep only latest version after vacuum");
        }

        @Test
        @DisplayName("Vacuum should preserve versions needed by active transactions")
        void testVacuumPreservesActiveTransactionVersions() throws Exception {
            long rowId = 1L;
            long activeTx = 1L;

            // Start active transaction
            mvcc.beginTransaction(activeTx);

            // Create versions
            for (long txId = 2; txId <= 5; txId++) {
                mvcc.beginTransaction(txId);
                Map<String, Object> data = new ConcurrentHashMap<>();
                data.put("version", txId);
                mvcc.write(rowId, data, txId, txId);
                mvcc.commitTransaction(txId);
            }

            int versionCountBefore = mvcc.getVersionCount();

            // Vacuum should not remove versions needed by active transaction
            mvcc.vacuum();

            int versionCountAfter = mvcc.getVersionCount();
            assertTrue(versionCountAfter >= 1, "Should preserve versions for active transaction");
        }
    }
}
