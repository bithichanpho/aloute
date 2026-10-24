package com.aloute.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CallHistoryMigrationIT {
    @Test
    void preexistingDuplicateHistoryAbortsMigrationWithoutDeletingOrRewritingMessages() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url != null, "Disposable PostgreSQL not configured");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aloute_quality_[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Refusing non-disposable/local database");
        String user = System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME");
        String password = System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD");
        String name = "aloute_quality_duplicates_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE DATABASE " + name);
        try {
            String isolated = url.substring(0, url.lastIndexOf('/') + 1) + name;
            var dataSource = new DriverManagerDataSource(isolated, user, password);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("20261103").cleanDisabled(true).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            for (String id : java.util.List.of("old-1", "old-2")) {
                jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('chat_messages',?,?::jsonb)",
                        id, "{\"id\":\"" + id + "\",\"callId\":\"legacy-duplicate\",\"messageType\":\"CALL\",\"content\":\"preserve me\"}");
            }
            assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration").cleanDisabled(true).load().migrate());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app_document_records WHERE payload->>'content'='preserve me'", Integer.class));
            assertNull(jdbc.queryForObject("SELECT to_regclass('public.ux_doc_chat_call_id')::text", String.class));
        } finally {
            // Only this random database created above is removed; never the supplied test DB or any shared DB.
            admin.execute("DROP DATABASE " + name);
        }
    }

    @Test
    void historicalCallIdsAreReservedWithoutReplayingHistoryOrNotifications() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url != null, "Disposable PostgreSQL not configured");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aloute_quality_[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Refusing non-disposable/local database");
        String user=System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"), password=System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        String name="aloute_quality_historical_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE "+name);
        try {
            var dataSource=new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/')+1)+name,user,password);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("20261104").cleanDisabled(true).load().migrate();
            var jdbc=new JdbcTemplate(dataSource);
            String payload="{\"id\":\"legacy\",\"messageType\":\"CALL\",\"callId\":\"old-call\",\"senderId\":\"1\",\"receiverId\":\"2\",\"callStatus\":\"MISSED\",\"content\":\"unchanged\"}";
            jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('chat_messages','legacy',?::jsonb)",payload);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
            assertEquals("COMPLETED",jdbc.queryForObject("SELECT processing_status FROM call_sessions WHERE call_id='old-call'",String.class));
            assertFalse(new com.aloute.messaging.calls.infrastructure.JdbcCallSessions(jdbc).register("old-call",1L,2L,"video"));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_document_records WHERE payload->>'content'='unchanged'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_outbox",Integer.class));
        } finally { admin.execute("DROP DATABASE "+name); }
    }
}
