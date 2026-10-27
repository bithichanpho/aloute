package com.aloute.schema;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Owns only the random database it creates, on an explicitly opted-in local test server. */
final class DisposablePostgres implements AutoCloseable {
    final DriverManagerDataSource source;
    final JdbcTemplate jdbc;
    private final JdbcTemplate admin;
    private final String database;

    static DisposablePostgres create() {
        String url=System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url!=null,"Disposable local PostgreSQL not configured");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]+/aloute_quality_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Only a local aloute_quality_* test database is allowed");
        String username=System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME");
        String password=System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD");
        Assumptions.assumeTrue(username!=null && password!=null,"Disposable database credentials required");
        String prefix=url.substring(0,url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(new DriverManagerDataSource(prefix+"postgres",username,password));
        String database="aloute_quality_migration_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+database);
        return new DisposablePostgres(admin,database,new DriverManagerDataSource(prefix+database,username,password));
    }

    private DisposablePostgres(JdbcTemplate admin,String database,DriverManagerDataSource source) {
        this.admin=admin; this.database=database; this.source=source; this.jdbc=new JdbcTemplate(source);
    }

    FluentConfiguration migrations() {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public").outOfOrder(true)
                .validateOnMigrate(true).cleanDisabled(true);
    }

    public void close() {
        if (!database.matches("aloute_quality_migration_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test DB cleanup");
        admin.execute("DROP DATABASE "+database+" WITH (FORCE)");
    }
}
