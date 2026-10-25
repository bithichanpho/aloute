package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="ALOUTE_SCHEMA_VALIDATION_URL",matches=".+")
class RegistrationEmailMigrationIT {
    @Test void expandPreservesAllRowsAndAcceptsTheExistingApiEmailLength() {
        String base = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        if(!base.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aloute_quality_[A-Za-z0-9_]+")) throw new IllegalArgumentException("Test DB only");
        String prefix = base.substring(0, base.lastIndexOf('/') + 1);
        var admin = jdbc(prefix+"postgres");
        String database = "aloute_quality_registration_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        try {
            var sql = jdbc(prefix+database);
            Flyway.configure().dataSource(sql.getDataSource()).target("20261107").load().migrate();
            sql.update("INSERT INTO roles(name) VALUES ('ROLE_USER')");
            sql.update("INSERT INTO users(username,email,password,full_name,role_id) VALUES ('email-original','original@example.invalid','test-only','Original', (SELECT id FROM roles WHERE name='ROLE_USER'))");
            sql.update("INSERT INTO pending_users(username,email,password,full_name,verification_token,verification_token_expiry,verification_email_sent_at,expires_at) VALUES ('pending-original','pending@example.invalid','test-only','Pending','test-hash',now()+interval '10 minutes',now(),now()+interval '1 day')");
            String before = fingerprint(sql);
            String longEmail = "person@" + "a".repeat(60) + ".another-label-with-more-than-forty-characters.example.invalid";
            assertTrue(longEmail.length()>100 && longEmail.length()<=254);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql.update(
                    "INSERT INTO users(username,email,password,full_name,role_id) VALUES ('long-user',?,'test-only','Long',(SELECT id FROM roles WHERE name='ROLE_USER'))",longEmail));
            Flyway.configure().dataSource(sql.getDataSource()).load().migrate();
            assertEquals(before, fingerprint(sql), "DDL must not alter or remove any original row");
            assertEquals(java.util.List.of(254,254), sql.queryForList("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('users','pending_users') AND column_name='email' ORDER BY table_name",Integer.class));
            sql.update("INSERT INTO users(username,email,password,full_name,role_id) VALUES ('long-user',?,'test-only','Long',(SELECT id FROM roles WHERE name='ROLE_USER'))",longEmail);
            sql.update("INSERT INTO pending_users(username,email,password,full_name,verification_token,verification_token_expiry,verification_email_sent_at,expires_at) VALUES ('long-pending',?,'test-only','Long pending','test-hash-2',now()+interval '10 minutes',now(),now()+interval '1 day')",longEmail);
            assertEquals(2,sql.queryForObject("SELECT count(*) FROM users",Integer.class));
            assertEquals(2,sql.queryForObject("SELECT count(*) FROM pending_users",Integer.class));
            assertEquals(longEmail,sql.queryForObject("SELECT email FROM users WHERE username='long-user'",String.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql.update("UPDATE users SET email='original@example.invalid' WHERE username='long-user'"));
            // A customized installation with wider columns must remain wider if this DDL is replayed.
            sql.execute("ALTER TABLE users ALTER COLUMN email TYPE varchar(512)");
            try(var input=getClass().getResourceAsStream("/db/migration/V20261108__expand_account_email_length.sql")) {
                sql.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
            assertEquals(512,sql.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='email'",Integer.class));
        } finally {
            if(!database.matches("aloute_quality_registration_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe database cleanup");
            admin.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }
    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"),System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD")));
    }
    private static String fingerprint(JdbcTemplate sql) {
        return sql.queryForObject("SELECT (SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM users u) || (SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM pending_users p)",String.class);
    }
}
