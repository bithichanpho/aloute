package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentDateTimeMigrationIT {
    @Test
    void upgradePreservesEveryPayloadAndMetadataAndOffersMatchingIndexes() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url != null, "Disposable PostgreSQL is required");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aloute_quality_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Refusing non-disposable/local database");
        String user = System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME");
        String password = System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        String name = "aloute_quality_datetime_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + name);
        try {
            var source = new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/')+1)+name,user,password);
            Flyway.configure().dataSource(source).target("20261105").cleanDisabled(true).load().migrate();
            var jdbc = new JdbcTemplate(source);
            for (String value : java.util.List.of("[2026,10,23,0,21,23,183036800]", "\"2026-10-23T00:21:23.183036801\"", "null", "\"bad\"")) {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('posts',?,?::jsonb)",
                        id,"{\"id\":\""+id+"\",\"content\":\"preserve me\",\"createdAt\":"+value+"}");
            }
            String fingerprint = "SELECT md5(string_agg(ROW(collection_name,document_id,payload,created_at,updated_at)::text,',' ORDER BY collection_name,document_id)) FROM app_document_records";
            String before = jdbc.queryForObject(fingerprint,String.class);
            Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
            assertEquals(before,jdbc.queryForObject(fingerprint,String.class));
            assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM app_document_records",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM app_document_records WHERE aloute_jsonb_local_datetime_key(payload->'createdAt') IS NOT NULL",Integer.class));
            // 14 from V20261106 plus the two repost indexes from V20261110.
            assertEquals(16,jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='app_document_records' AND indexname LIKE '%_v2'",Integer.class));
            assertNotNull(jdbc.queryForObject("SELECT to_regclass('ix_doc_posts_created')::text",String.class));
            new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(tx -> {
                        tx.setRollbackOnly();
                        jdbc.execute("SET LOCAL enable_seqscan=off"); // Index eligibility only, not a performance claim.
                        String plan = String.join("\n",jdbc.queryForList("EXPLAIN SELECT document_id FROM app_document_records WHERE collection_name='posts' AND aloute_jsonb_local_datetime_key(payload->'createdAt') IS NOT NULL ORDER BY aloute_jsonb_local_datetime_key(payload->'createdAt') DESC,document_id DESC LIMIT 20",String.class));
                        assertTrue(plan.contains("ix_doc_posts_created_v2"),plan);
                        assertFalse(plan.contains("Sort"),plan);
                    });
        } finally {
            // Only the random test database created above; no supplied/shared database is dropped.
            admin.execute("DROP DATABASE " + name);
        }
    }
}
