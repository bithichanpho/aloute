package com.aloute.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reports the persistent PostgreSQL target/counts without logging credentials or a JDBC URL. */
@Component
public class NotificationStorageStartupCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(NotificationStorageStartupCheck.class);
    private final JdbcTemplate jdbc;

    public NotificationStorageStartupCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var state = jdbc.queryForMap("""
                    select current_database() as database_name,
                           current_schema() as schema_name,
                           (select max(id) from notifications) as latest_notification_id,
                           (select count(*) from notification_outbox) as outbox_rows
                    """);
            log.info("[ALOUTE] Durable notification storage ready: database={}, schema={}, latestNotificationId={}, outboxRows={}",
                    state.get("database_name"), state.get("schema_name"),
                    state.get("latest_notification_id"), state.get("outbox_rows"));
        } catch (DataAccessException failure) {
            // The application owns persistence via PostgreSQL/Flyway. This diagnostic must not
            // mask the original startup failure if schema provisioning is incomplete.
            log.warn("[ALOUTE] Could not verify notification persistence tables at startup (cause={})",
                    failure.getClass().getSimpleName());
        }
    }
}
