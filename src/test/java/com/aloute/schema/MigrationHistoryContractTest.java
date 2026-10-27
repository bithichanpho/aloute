package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Detects reused versions, edited deployed SQL and stale build resources before release. */
class MigrationHistoryContractTest {
    private static final Path DIRECTORY = Path.of("src/main/resources/db/migration");
    private static final Pattern NAME = Pattern.compile("V([0-9]+(?:_[0-9]+)*)__([A-Za-z0-9_-]+)\\.sql");

    @Test
    void versionsAreUniqueUsingFlywaysOwnVersionNormalization() throws Exception {
        var versions = new HashMap<MigrationVersion,String>();
        try (var files = Files.list(DIRECTORY)) {
            for (var path : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String name = path.getFileName().toString();
                var matcher = NAME.matcher(name);
                assertTrue(matcher.matches(), "Invalid migration filename: " + name);
                String previous = versions.putIfAbsent(MigrationVersion.fromVersion(matcher.group(1)), name);
                assertNull(previous, "Reused migration version: " + previous + " / " + name);
            }
        }
        assertFalse(versions.isEmpty());
    }

    @Test
    void alreadyDeployedMigrationsCannotBeDeletedRenamedOrEdited() throws Exception {
        var baseline = new Properties();
        try (var input = new ClassPathResource("migration-baseline-20261110.properties").getInputStream()) {
            baseline.load(input);
        }
        assertEquals(28, baseline.size(), "The deployed baseline must not be regenerated to hide SQL changes");
        for (String name : baseline.stringPropertyNames()) {
            Path path = DIRECTORY.resolve(name);
            assertTrue(Files.isRegularFile(path), "Deployed migration missing: " + name);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized(Files.readAllBytes(path))));
            assertEquals(baseline.getProperty(name), digest, "Deployed SQL changed: " + name + "; add a new migration instead");
        }
    }

    @Test
    void newMigrationsMustAdvanceTheDeployedVersion() throws Exception {
        var baseline = new Properties();
        try (var input = new ClassPathResource("migration-baseline-20261110.properties").getInputStream()) {
            baseline.load(input);
        }
        try (var files = Files.list(DIRECTORY)) {
            for (var path : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String name = path.getFileName().toString();
                if (baseline.containsKey(name)) continue;
                var matcher = NAME.matcher(name);
                assertTrue(matcher.matches(), name);
                assertTrue(MigrationVersion.fromVersion(matcher.group(1)).compareTo(MigrationVersion.fromVersion("20261110")) > 0,
                        "New migration must be newer than deployed history: " + name);
            }
        }
    }

    @Test
    void runtimeMigrationResourcesExactlyMatchCurrentSource() throws Exception {
        var runtime = new TreeMap<String,byte[]>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            try (var input = resource.getInputStream()) {
                assertNull(runtime.put(resource.getFilename(), normalized(input.readAllBytes())), "Duplicate runtime resource: " + resource.getFilename());
            }
        }
        var source = new TreeMap<String,byte[]>();
        try (var files = Files.list(DIRECTORY)) {
            for (var path : files.filter(p -> p.toString().endsWith(".sql")).toList())
                source.put(path.getFileName().toString(), normalized(Files.readAllBytes(path)));
        }
        assertEquals(source.keySet(), runtime.keySet(), "Stale/missing target resources: rebuild from clean output before release");
        for (String name : source.keySet()) assertArrayEquals(source.get(name), runtime.get(name), name);
    }

    @Test
    void startupKeepsValidationAndCleanProtectionEnabled() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertEquals("true", properties.getProperty("spring.flyway.validate-on-migrate"));
        assertEquals("true", properties.getProperty("spring.flyway.clean-disabled"));
        assertEquals("true", properties.getProperty("spring.flyway.out-of-order"), "Late 20261104.1 must remain applicable to existing databases");
    }

    private byte[] normalized(byte[] bytes) {
        String text = new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
