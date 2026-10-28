package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class LocalServicesAutoStarterTest {
    @TempDir Path root;
    private final LocalServicesAutoStarter.ServiceRunner runner = mock(LocalServicesAutoStarter.ServiceRunner.class);

    private LocalServicesAutoStarter starter() {
        return new LocalServicesAutoStarter("Windows 11", root, runner);
    }

    private void helpers() throws Exception {
        Files.createDirectories(root.resolve("deploy"));
        for (String name : List.of("start-local-redis.ps1", "start-local-moderation.ps1")) {
            Files.writeString(root.resolve("deploy").resolve(name), "# Test fixture; never executed\n");
        }
    }

    @Test
    void runningServicesAreReusedWithoutLaunchingAnything() {
        when(runner.isReady(any())).thenReturn(true);
        starter().start(new MockEnvironment());
        var service = ArgumentCaptor.forClass(LocalServicesAutoStarter.LocalService.class);
        verify(runner, times(2)).isReady(service.capture());
        assertEquals(List.of("Redis", "Moderation"), service.getAllValues().stream().map(s -> s.name()).toList());
        verify(runner, never()).start(any(), any(), any());
    }

    @Test
    void unavailableServicesAreStartedThenCheckedBeforeContinuing() throws Exception {
        helpers();
        when(runner.isReady(any())).thenReturn(false, true, false, true);
        starter().start(new MockEnvironment());
        var order = inOrder(runner);
        for (String name : List.of("Redis", "Moderation")) {
            order.verify(runner).isReady(argThat(s -> s.name().equals(name)));
            order.verify(runner).start(argThat(s -> s.name().equals(name)),
                    eq(root.resolve("deploy/start-local-" + name.toLowerCase() + ".ps1")), eq(Duration.ofMinutes(5)));
            order.verify(runner).isReady(argThat(s -> s.name().equals(name)));
        }
    }

    @Test
    void remoteAuthenticatedOrCustomServicesAreNeverReplacedWithLocalOnes() {
        for (String[] endpoints : List.of(
                new String[]{"rediss://user:private-fixture@shared.example:6379", "https://moderation.example:8000"},
                new String[]{"redis://localhost:6380", "http://localhost:8001"},
                new String[]{"redis://:private-fixture@localhost:6379", "http://localhost:8000/custom"})) {
            starter().start(new MockEnvironment().withProperty("spring.data.redis.url", endpoints[0])
                    .withProperty("app.moderation.service-url", endpoints[1]));
        }
        verifyNoInteractions(runner);
    }

    @Test
    void productionDockerAndExplicitOptOutDoNotLaunchWorkstationServices() {
        for (String profile : List.of("prod", "production", "call-distributed", "test")) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            starter().start(environment);
        }
        starter().start(new MockEnvironment().withProperty("app.local-services.auto-start", "false"));
        starter().start(new MockEnvironment().withProperty("spring.docker.compose.enabled", "true"));
        starter().start(new MockEnvironment().withProperty("app.cache.redis.enabled", "false")
                .withProperty("app.moderation.enabled", "false"));
        verifyNoInteractions(runner);
    }

    @Test
    void helperFailureOrUnhealthyServiceStopsStartupWithAnActionableError() throws Exception {
        doThrow(new IllegalStateException("Missing setup. Set LOCAL_SERVICES_AUTO_START=false to manage services yourself."))
                .when(runner).start(any(), any(), any());
        var missing = assertThrows(IllegalStateException.class, () -> starter().start(new MockEnvironment()));
        assertTrue(missing.getMessage().contains("LOCAL_SERVICES_AUTO_START=false"));
        doNothing().when(runner).start(any(), any(), any());
        helpers();
        var unhealthy = assertThrows(IllegalStateException.class, () -> starter().start(new MockEnvironment()));
        assertTrue(unhealthy.getMessage().contains("did not become ready"));
        verify(runner, never()).start(argThat(s -> s.name().equals("Moderation")), any(), any());
    }

    @Test
    void windowsLinuxAndMacAllUseTheSameStartupFlow() {
        when(runner.isReady(any())).thenReturn(true);
        for (String os : List.of("Windows 11", "Linux", "Mac OS X")) {
            new LocalServicesAutoStarter(os, root, runner).start(new MockEnvironment());
        }
        verify(runner, times(6)).isReady(any());
    }

    @Test
    void realSpringStartupReadsConfigurationAndStartsServicesBeforeCreatingBeans() throws Exception {
        helpers();
        Path config = root.resolve("local-startup.properties");
        Files.writeString(config, "app.moderation.enabled=false\napp.local-services.startup-timeout=45s\n");
        when(runner.isReady(any())).thenReturn(false, true);
        var application = new SpringApplication(StartupProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("spring.config.location", config.toUri().toString(),
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR"));
        application.addListeners(starter());
        application.addInitializers(context -> {
            // Initializers execute before refresh; the environment-prepared listener must already have finished.
            verify(runner).start(argThat(s -> s.name().equals("Redis")), any(), eq(Duration.ofSeconds(45)));
            verify(runner, never()).start(argThat(s -> s.name().equals("Moderation")), any(), any());
        });
        try (var context = application.run()) { assertEquals("ready", context.getBean("startupProbe")); }
    }

    @Configuration(proxyBeanMethods = false)
    static class StartupProbe {
        @Bean String startupProbe() { return "ready"; }
    }
}
