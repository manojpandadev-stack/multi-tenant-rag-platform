package com.docmind.testutil;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Centralized test data cleanup utility.
 *
 * Deletes from all tables in FK-safe order to avoid constraint violations
 * when new tables are added across stages. Every integration test should
 * call this instead of implementing its own cleanup logic.
 *
 * FK-safe delete order:
 * 1. usage_periods (references organizations)
 * 2. semantic_cache (references organizations)
 * 3. document_chunks (references documents, organizations)
 * 4. documents (references organizations)
 * 5. users (references organizations)
 * 6. organizations (no FK references)
 *
 * Usage in tests:
 *   @Autowired TestDataCleaner cleaner;
 *   @BeforeEach void setUp() { cleaner.deleteAll(); }
 */
@Component
public class TestDataCleaner {

    private final JdbcTemplate jdbc;

    public TestDataCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Delete all data from every table in FK-safe order.
     * Uses TRUNCATE ... CASCADE for speed, falling back to DELETE FROM
     * for tables without TRUNCATE support or when cascade isn't desired.
     */
    public void deleteAll() {
        // Delete in FK-safe reverse-dependency order
        // Using DELETE FROM (not TRUNCATE) to work with Hibernate's persistence context
        String[] tables = {
            "usage_periods",
            "semantic_cache",
            "document_chunks",
            "documents",
            "users",
            "organizations"
        };

        for (String table : tables) {
            try {
                jdbc.execute("DELETE FROM " + table);
            } catch (Exception e) {
                // Table may not exist yet (e.g. during early schema setup) — ignore
            }
        }
    }

    /**
     * Delete from specific tables only. Useful when a test doesn't need
     * a full clean (e.g. cache-only tests that don't touch documents).
     */
    public void deleteFrom(String... tables) {
        for (String table : tables) {
            try {
                jdbc.execute("DELETE FROM " + table);
            } catch (Exception e) {
                // Table may not exist — ignore
            }
        }
    }
}
