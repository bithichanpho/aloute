package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Opt-in disposable PostgreSQL only. Every SQL cleanup assertion is rolled back. */
class NotificationBulkCleanupIT {
    @Test
    void repositoryFactoryValidatesJpqlAndExecutesLegacyBulkAdaptersInOneLocalTransaction() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        String username = System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME");
        String password = System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD");
        Assumptions.assumeTrue(url != null && username != null && password != null,
                "Requires the disposable local PostgreSQL environment from validate-empty-schema.ps1");
        if (!(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:")
                || url.startsWith("jdbc:postgresql://[::1]:"))) {
            throw new IllegalArgumentException("Refusing non-local database; never run this test against production");
        }
        var dataSource = new DriverManagerDataSource(url, username, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public").cleanDisabled(true).load().migrate();
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.aloute.model.entity");
        factory.setPersistenceUnitName("notification-bulk-test");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.default_schema", "public");
        properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");
        properties.setProperty("hibernate.show_sql", "false");
        factory.setJpaProperties(properties);
        try {
            factory.afterPropertiesSet();
            try (var manager = factory.getObject().createEntityManager()) {
                var repositories = new JpaRepositoryFactory(manager);
                var notifications = repositories.getRepository(INotificationRepository.class);
                var outbox = repositories.getRepository(INotificationOutboxRepository.class);
                String absentReference = "test-" + UUID.randomUUID();
                manager.getTransaction().begin();
                try {
                    assertEquals(0L, notifications.deleteByReferenceIdIn(List.of(absentReference)));
                    assertEquals(0L, outbox.deleteByReferenceIdIn(List.of(absentReference)));
                    assertEquals(0L, notifications.deleteByReferenceIdAndTypeIn(absentReference, List.of("TEST")));
                    assertEquals(0L, outbox.deleteByReferenceIdAndNotificationTypeIn(absentReference, List.of("TEST")));
                } finally {
                    if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                }
            }
        } finally {
            factory.destroy();
        }
    }
}
