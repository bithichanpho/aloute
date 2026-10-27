package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs only on owned disposable databases; never reads the application's DB_URL. */
class MigrationCompatibilityIT {
    @TempDir Path temporary;

    @Test
    void upgradesExistingDataReservesHistoricalCallIdsAndRepeatsWithoutChanges() throws Exception {
        try (var database=DisposablePostgres.create()) {
            database.migrations().target("20261102").load().migrate();
            seed(database);
            String before=fingerprint(database);
            database.migrations().load().migrate();
            assertEquals(before,fingerprint(database));
            assertEquals("COMPLETED",database.jdbc.queryForObject(
                    "SELECT processing_status FROM call_sessions WHERE call_id='compatibility-call'",String.class));
            assertEquals(0,database.migrations().load().migrate().migrationsExecuted);
            assertTrue(database.migrations().load().validateWithResult().validationSuccessful);
        }
    }

    @Test
    void lateCompatibilityMigrationWorksAfterNewerVersionsWereAlreadyApplied() throws Exception {
        try (var database=DisposablePostgres.create()) {
            database.migrations().target("20261102").load().migrate();
            seed(database);
            // An older deployment already owned call_sessions but had no 20261104.1 history row.
            database.jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V20261104_1__durable_call_sessions.sql")));
            Path legacy=copyMigrations(true);
            database.migrations().locations("filesystem:"+legacy.toAbsolutePath()).load().migrate();
            String before=fingerprint(database);
            assertEquals(1,database.migrations().load().migrate().migrationsExecuted);
            assertEquals(before,fingerprint(database));
            assertEquals(1,database.jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version='20261104.1' AND success",Integer.class));
            assertEquals(0,database.migrations().load().migrate().migrationsExecuted);
        }
    }

    @Test
    void changedHistoricalChecksumIsRejectedWithoutRepairOrDataChanges() throws Exception {
        try (var database=DisposablePostgres.create()) {
            database.migrations().target("20261102").load().migrate();
            seed(database);
            String before=fingerprint(database);
            int history=database.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class);
            Path changed=copyMigrations(false);
            Files.writeString(changed.resolve("V20261102__postgres_jsonb_document_store.sql"),
                    "\n-- Simulated different source on a second machine\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
            var mismatched=database.migrations().locations("filesystem:"+changed.toAbsolutePath()).load();
            assertFalse(mismatched.validateWithResult().validationSuccessful);
            assertThrows(FlywayException.class,mismatched::migrate);
            assertEquals(history,database.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class));
            assertEquals(before,fingerprint(database));
        }
    }

    @Test
    void twoApplicationStartsSerializeOneMigrationHistory() throws Exception {
        try (var database=DisposablePostgres.create();var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Integer> migrate=()->{ start.await(); return database.migrations().load().migrate().migrationsExecuted; };
            var first=executor.submit(migrate); var second=executor.submit(migrate); start.countDown();
            int applied=first.get(90,TimeUnit.SECONDS)+second.get(90,TimeUnit.SECONDS);
            assertEquals(database.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class),applied);
            assertEquals(0,database.jdbc.queryForObject(
                    "SELECT count(*) FROM (SELECT version FROM flyway_schema_history GROUP BY version HAVING count(*)>1) duplicates",Integer.class));
            assertTrue(database.migrations().load().validateWithResult().validationSuccessful);
        }
    }

    private Path copyMigrations(boolean omitLateMigration) throws Exception {
        Path location=Files.createDirectory(temporary.resolve("migrations"));
        try (var files=Files.list(Path.of("src/main/resources/db/migration"))) {
            for (var file:files.filter(p->p.toString().endsWith(".sql")).toList()) {
                if (omitLateMigration && file.getFileName().toString().equals("V20261104_1__durable_call_sessions.sql")) continue;
                Files.copy(file,location.resolve(file.getFileName()));
            }
        }
        return location;
    }

    private void seed(DisposablePostgres database) {
        database.jdbc.update("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO NOTHING");
        database.jdbc.update("INSERT INTO users(username,email,password,full_name,role_id) SELECT 'migration-owner','owner@example.invalid','test-only','Owner',id FROM roles WHERE name='ROLE_USER'");
        database.jdbc.update("INSERT INTO users(username,email,password,full_name,role_id) SELECT 'migration-viewer','viewer@example.invalid','test-only','Viewer',id FROM roles WHERE name='ROLE_USER'");
        Long caller=database.jdbc.queryForObject("SELECT id FROM users WHERE username='migration-owner'",Long.class);
        Long callee=database.jdbc.queryForObject("SELECT id FROM users WHERE username='migration-viewer'",Long.class);
        database.jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('posts','compatibility-post',?::jsonb)",
                "{\"id\":\"compatibility-post\",\"userId\":\""+caller+"\",\"content\":\"preserve post\",\"createdAt\":\"2026-10-25T08:00:00\"}");
        database.jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('chat_messages','compatibility-message',?::jsonb)",
                "{\"id\":\"compatibility-message\",\"senderId\":\""+caller+"\",\"receiverId\":\""+callee+"\",\"messageType\":\"CALL\",\"callId\":\"compatibility-call\",\"callStatus\":\"MISSED\",\"content\":\"preserve call\",\"sentAt\":\"2026-10-25T08:00:00\"}");
    }

    private String fingerprint(DisposablePostgres database) {
        return database.jdbc.queryForObject("SELECT md5((SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM users u)||"+
                "(SELECT jsonb_agg(jsonb_build_object('collection',collection_name,'id',document_id,'payload',payload) ORDER BY collection_name,document_id)::text FROM app_document_records))",String.class);
    }
}
