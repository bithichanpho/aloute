package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.mock.env.MockEnvironment;

/** Explicit workstation verification; never runs against local services in the normal test suite. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "aloute.local-services.smoke", matches = "true")
class LocalServicesAutoStarterIT {
    @Test
    void realWindowsHelpersAndHealthChecksWorkWithoutStoppingExistingServices() {
        var runner = new LocalServicesAutoStarter.WorkstationRunner();
        for (var service : List.of(
                new LocalServicesAutoStarter.LocalService("Redis", URI.create("redis://localhost:6379"), "start-local-redis.ps1"),
                new LocalServicesAutoStarter.LocalService("Moderation", URI.create("http://localhost:8000"), "start-local-moderation.ps1"))) {
            runner.start(service, Path.of("deploy", service.helper()).toAbsolutePath(), Duration.ofSeconds(90));
            assertTrue(runner.isReady(service), service.name());
        }
    }

    @Test
    void realBootstrapReusesTheInstalledHealthyServices() {
        new LocalServicesAutoStarter().start(new MockEnvironment());
    }
}
