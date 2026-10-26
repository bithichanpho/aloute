package com.aloute.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cluster-wide mutual exclusion for {@code @Scheduled} jobs.
 *
 * <p>Only methods that also carry {@code @SchedulerLock} are locked. Jobs that are intentionally
 * per-node (in-memory cleanup, presence lease refresh) or that already claim work through a
 * PostgreSQL lease (moderation queues, account-cleanup retries) are left alone on purpose.
 *
 * <p>PostgreSQL is used as the lock store because it is always present, including when the
 * {@code no-redis} profile is active. {@code usingDbTime()} makes the database clock the single
 * time source, so clock skew between replicas cannot cause two nodes to hold the same lock.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(
            DataSource dataSource, @Value("${DB_SCHEMA:public}") String schema) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(schema + ".shedlock")
                        .usingDbTime()
                        .build());
    }
}
