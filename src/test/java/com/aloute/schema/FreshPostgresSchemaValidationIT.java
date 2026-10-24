package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Fresh-schema integration smoke test. Maven Failsafe discovers this *IT class during verify.
 * CI and deploy/scripts/validate-empty-schema.ps1 provide a disposable local PostgreSQL database.
 * Never point this test at production.
 */
class FreshPostgresSchemaValidationIT {
    @Test
    void flywayCreatesFreshSchemaThatHibernateCanValidate() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        String username = System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME");
        String password = System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD");
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:postgresql:"),
                "Run deploy/scripts/validate-empty-schema.ps1 to provide a disposable PostgreSQL database");
        Assumptions.assumeTrue(username != null && !username.isBlank(), "Validation DB username is required");
        Assumptions.assumeTrue(password != null && !password.isBlank(), "Validation DB password is required");
        if (!(url.startsWith("jdbc:postgresql://127.0.0.1:")
                || url.startsWith("jdbc:postgresql://localhost:")
                || url.startsWith("jdbc:postgresql://[::1]:"))) {
            throw new IllegalArgumentException(
                    "This opt-in test refuses non-local DB URLs. Use the disposable-db script; never validate/modify production here.");
        }

        DataSource dataSource = new DriverManagerDataSource(url, username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .load()
                .migrate();

        LocalContainerEntityManagerFactoryBean entityManagerFactory = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setPackagesToScan("com.aloute.model.entity");
        entityManagerFactory.setPersistenceUnitName("aloute-schema-validation");
        entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.put("hibernate.hbm2ddl.auto", "validate");
        properties.put("hibernate.default_schema", "public");
        properties.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.jdbc.time_zone", "UTC");
        properties.put("hibernate.show_sql", "false");
        entityManagerFactory.setJpaProperties(properties);
        try {
            entityManagerFactory.afterPropertiesSet();
            assertNotNull(entityManagerFactory.getObject(), "Hibernate EntityManagerFactory must initialize in validate mode");
        } finally {
            entityManagerFactory.destroy();
        }
    }
}
