package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class PortableLocalServicesRunnerTest {
    @TempDir Path root;
    private final PortableLocalServicesRunner.Commands commands = mock(PortableLocalServicesRunner.Commands.class);

    private PortableLocalServicesRunner runner(String os) {
        var runner = new PortableLocalServicesRunner(os, root.resolve("cache"), commands);
        runner.configure(new MockEnvironment().withProperty("OPENAI_API_KEY", "private-test-key")
                .withProperty("app.moderation.api-token", "private-test-token-" + "x".repeat(40)));
        return runner;
    }

    private LocalServicesAutoStarter.LocalService service(String name) {
        return new LocalServicesAutoStarter.LocalService(name,
                URI.create(name.equals("Redis") ? "redis://localhost:6379" : "http://localhost:8000"), "missing-helper.ps1");
    }

    @Test
    void freshWindowsMacAndLinuxNeedNoWslOrProjectHelpers() {
        when(commands.dockerAvailable()).thenReturn(true);
        for (String os : List.of("Windows 11", "Mac OS X", "Linux")) {
            runner(os).start(service("Moderation"), root.resolve("missing-helper.ps1"), Duration.ofMinutes(5));
        }
        var command = ArgumentCaptor.forClass(List.class);
        var environment = ArgumentCaptor.forClass(Map.class);
        verify(commands, times(3)).run(command.capture(), any(), environment.capture(), eq(Duration.ofMinutes(5)), any());
        for (int i = 0; i < 3; i++) {
            assertTrue(command.getAllValues().get(i).containsAll(List.of("docker", "compose", "--build", "--wait", "moderation")));
            assertFalse(command.getAllValues().get(i).toString().contains("private-test"));
            assertEquals("private-test-key", environment.getAllValues().get(i).get("OPENAI_API_KEY"));
        }
    }

    @Test
    void jarContainsOnlyTheRequiredBuildAssetsAndNoPrivateConfiguration() throws Exception {
        Path extracted = PortableLocalServicesRunner.extractAssets(root);
        try (var files = Files.walk(extracted)) {
            var relative = files.filter(Files::isRegularFile).map(extracted::relativize)
                    .map(p -> p.toString().replace('\\', '/')).sorted().toList();
            assertEquals(List.of("docker-compose.yml", "moderation/.dockerignore", "moderation/Dockerfile",
                    "moderation/main.py", "moderation/requirements.txt"), relative);
        }
        assertArrayEquals(Files.readAllBytes(Path.of("moderation/main.py")), Files.readAllBytes(extracted.resolve("moderation/main.py")));
        assertEquals(extracted, PortableLocalServicesRunner.extractAssets(root));
        assertFalse(Files.readString(extracted.resolve("docker-compose.yml")).contains("private-test"));
    }

    @Test
    void redisCanStartWithoutAnyAiCredentials() {
        when(commands.dockerAvailable()).thenReturn(true);
        var runner = new PortableLocalServicesRunner("Linux", root, commands);
        runner.configure(new MockEnvironment());
        runner.start(service("Redis"), root.resolve("missing.ps1"), Duration.ofMinutes(5));
        verify(commands).run(argThat(args -> args.getLast().equals("redis")), any(), eq(Map.of()), any(), any());
    }

    @Test
    void missingDockerOrMissingAiConfigurationGivesAnActionableError() {
        var missingDocker = assertThrows(IllegalStateException.class,
                () -> runner("Linux").start(service("Redis"), root.resolve("missing.ps1"), Duration.ofMinutes(5)));
        assertTrue(missingDocker.getMessage().contains("install and start Docker"));
        when(commands.dockerAvailable()).thenReturn(true);
        var runner = new PortableLocalServicesRunner("Linux", root, commands);
        runner.configure(new MockEnvironment());
        var missingKey = assertThrows(IllegalStateException.class,
                () -> runner.start(service("Moderation"), root.resolve("missing.ps1"), Duration.ofMinutes(5)));
        assertTrue(missingKey.getMessage().contains("OPENAI_API_KEY"));
        verify(commands, never()).run(any(), any(), any(), any(), any());
    }
}
