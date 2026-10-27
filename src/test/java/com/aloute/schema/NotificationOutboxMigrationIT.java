package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.model.entity.NotificationOutboxEvent;
import java.time.LocalDateTime;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Shared V20261111 compatibility, using only databases owned by DisposablePostgres. */
class NotificationOutboxMigrationIT {
    private static final String EVENT = "outbox-migration-fixture";

    @Test
    void upgradePreservesNotificationsAndSupportsTheExistingJpaWriter() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().target("20261110").load().migrate();
            seed(database);
            String before = fingerprint(database);

            assertEquals(1, database.migrations().load().migrate().migrationsExecuted);
            assertEquals(before, fingerprint(database));
            assertEquals(Boolean.TRUE, database.jdbc.queryForObject(
                    "SELECT in_app_delivered_at IS NULL FROM notification_outbox WHERE id=?", Boolean.class, EVENT));
            assertNotNull(database.jdbc.queryForObject(
                    "SELECT to_regclass('public.idx_notification_outbox_in_app_delivery')::text", String.class));

            // Retry-counter updates must preserve an existing delivery checkpoint.
            LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 27, 8, 0);
            database.jdbc.update("UPDATE notification_outbox SET in_app_delivered_at=? WHERE id=?", deliveredAt, EVENT);
            var factory = entityManagerFactory(database);
            try {
                factory.afterPropertiesSet();
                try (var manager = factory.getObject().createEntityManager()) {
                    manager.getTransaction().begin();
                    var existing = manager.find(NotificationOutboxEvent.class, EVENT);
                    assertEquals("preserve outbox", existing.getContent());
                    existing.setAttemptCount(2);
                    var added = new NotificationOutboxEvent();
                    added.setReceiverId(existing.getReceiverId());
                    added.setSenderId(existing.getSenderId());
                    added.setNotificationType("FOLLOW");
                    added.setContent("old writer still works");
                    manager.persist(added);
                    manager.getTransaction().commit();
                    assertEquals(Boolean.TRUE, database.jdbc.queryForObject(
                            "SELECT in_app_delivered_at IS NULL FROM notification_outbox WHERE id=?",
                            Boolean.class, added.getId()));
                }
            } finally {
                factory.destroy();
            }
            assertEquals(deliveredAt, database.jdbc.queryForObject(
                    "SELECT in_app_delivered_at FROM notification_outbox WHERE id=?", LocalDateTime.class, EVENT));
            assertEquals(2, database.jdbc.queryForObject(
                    "SELECT attempt_count FROM notification_outbox WHERE id=?", Integer.class, EVENT));
            assertEquals("preserve notification", database.jdbc.queryForObject(
                    "SELECT content FROM notifications WHERE outbox_event_id=?", String.class, EVENT));
        }
    }

    @Test
    void alreadyAppliedSharedMigrationValidatesAndIsNotAppliedTwice() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().load().migrate();
            seed(database);
            String before = fingerprint(database);

            var nextStart = database.migrations().load();
            assertTrue(nextStart.validateWithResult().validationSuccessful);
            assertEquals(0, nextStart.migrate().migrationsExecuted);
            assertEquals(before, fingerprint(database));
            assertEquals(1, database.jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version='20261111' AND success", Integer.class));
        }
    }

    @Test
    void existingColumnAndIndexArePreservedWhenSqlWasAppliedBeforeFlywayRecordedIt() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().target("20261110").load().migrate();
            seed(database);
            database.jdbc.execute("ALTER TABLE notification_outbox ADD COLUMN in_app_delivered_at timestamp without time zone");
            database.jdbc.execute("CREATE INDEX idx_notification_outbox_in_app_delivery ON notification_outbox (in_app_delivered_at, next_attempt_at, created_at)");
            LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 27, 8, 0);
            database.jdbc.update("UPDATE notification_outbox SET in_app_delivered_at=? WHERE id=?", deliveredAt, EVENT);
            String before = fingerprint(database);

            assertEquals(1, database.migrations().load().migrate().migrationsExecuted);
            assertEquals(before, fingerprint(database));
            assertEquals(deliveredAt, database.jdbc.queryForObject(
                    "SELECT in_app_delivered_at FROM notification_outbox WHERE id=?", LocalDateTime.class, EVENT));
            assertTrue(database.migrations().load().validateWithResult().validationSuccessful);
        }
    }

    private LocalContainerEntityManagerFactoryBean entityManagerFactory(DisposablePostgres database) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(database.source);
        factory.setPackagesToScan("com.aloute.model.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.default_schema", "public");
        properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");
        factory.setJpaProperties(properties);
        return factory;
    }

    private void seed(DisposablePostgres database) {
        database.jdbc.update("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO NOTHING");
        database.jdbc.update("INSERT INTO users(username,email,password,full_name,role_id) SELECT 'outbox-migration-user','outbox@example.invalid','test-only','Outbox',id FROM roles WHERE name='ROLE_USER'");
        Long user = database.jdbc.queryForObject("SELECT id FROM users WHERE username='outbox-migration-user'", Long.class);
        database.jdbc.update("INSERT INTO notification_outbox(id,receiver_id,sender_id,notification_type,content) VALUES (?,?,?,'FOLLOW','preserve outbox')", EVENT, user, user);
        database.jdbc.update("INSERT INTO notifications(receiver_id,sender_id,type,content,outbox_event_id) VALUES (?,?,'FOLLOW','preserve notification',?)", user, user, EVENT);
    }

    private String fingerprint(DisposablePostgres database) {
        return database.jdbc.queryForObject("""
                SELECT md5(
                    (SELECT jsonb_agg(to_jsonb(o) - 'in_app_delivered_at' ORDER BY id)::text FROM notification_outbox o)
                    || (SELECT jsonb_agg(to_jsonb(n) ORDER BY id)::text FROM notifications n))
                """, String.class);
    }
}
