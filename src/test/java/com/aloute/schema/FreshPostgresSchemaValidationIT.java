package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
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
        try (var database = DisposablePostgres.create()) {
            DataSource dataSource = database.source;
            database.migrations().load().migrate();

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
}
