package com.aloute.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/** Ships the moderation build context inside the JAR; requires no workstation-specific paths. */
final class PortableLocalServicesRunner implements LocalServicesAutoStarter.ServiceRunner {
    private static final Logger log = LoggerFactory.getLogger(PortableLocalServicesRunner.class);
    private static final List<String> ASSETS = List.of("docker-compose.yml", "moderation/Dockerfile",
            "moderation/main.py", "moderation/requirements.txt", "moderation/.dockerignore");
    private final String osName;
    private final Path cacheRoot;
    private final Commands commands;
    private final LocalServicesAutoStarter.WorkstationRunner existing = new LocalServicesAutoStarter.WorkstationRunner();
    private Environment environment;

    PortableLocalServicesRunner(String osName, Path cacheRoot) { this(osName, cacheRoot, new SystemCommands()); }

    PortableLocalServicesRunner(String osName, Path cacheRoot, Commands commands) {
        this.osName = osName;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.commands = commands;
    }

    public void configure(Environment environment) { this.environment = environment; }

    public boolean isReady(LocalServicesAutoStarter.LocalService service) { return existing.isReady(service); }

    public void start(LocalServicesAutoStarter.LocalService service, Path helper, Duration timeout) {
        if (!commands.dockerAvailable()) {
            if (osName.toLowerCase(Locale.ROOT).startsWith("windows") && Files.isRegularFile(helper)) {
                log.info("Docker is unavailable; using the existing Windows/WSL helper for {}", service.name());
                existing.start(service, helper, timeout);
                return;
            }
            throw new IllegalStateException("Cannot start local " + service.name()
                    + ": install and start Docker with Compose, then run the app again. "
                    + "Redis and moderation are bundled with the app; no separate Python/Redis installation is needed. "
                    + "Set LOCAL_SERVICES_AUTO_START=false if you manage services yourself.");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        if ("Moderation".equals(service.name())) {
            String key = environment.getProperty("OPENAI_API_KEY", "").trim();
            String token = environment.getProperty("app.moderation.api-token",
                    environment.getProperty("MODERATION_API_TOKEN", "")).trim();
            if (key.isEmpty() || token.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("Configure OPENAI_API_KEY and a 32+ byte MODERATION_API_TOKEN "
                        + "in the app environment before starting moderation.");
            }
            variables.put("OPENAI_API_KEY", key);
            variables.put("MODERATION_API_TOKEN", token);
            for (String name : List.of("OPENAI_MODERATION_MODEL", "OPENAI_AUDIO_MODEL", "MODERATION_ALLOWED_MEDIA_HOSTS",
                    "OPENAI_MODERATION_TIMEOUT_SECONDS", "OPENAI_AUDIO_TIMEOUT_SECONDS")) {
                String value = environment.getProperty(name);
                if (value != null) variables.put(name, value);
            }
        }
        Path assets = extractAssets(cacheRoot);
        Path output = cacheRoot.resolve("startup-" + service.name().toLowerCase(Locale.ROOT) + ".log");
        List<String> command = new ArrayList<>(List.of("docker", "compose", "--project-name", "aloute-local-services",
                "--file", assets.resolve("docker-compose.yml").toString(),
                "up", "--detach", "--build", "--wait", "--wait-timeout", Long.toString(Math.max(1, timeout.toSeconds())),
                service.name().toLowerCase(Locale.ROOT)));
        log.info("Starting bundled {} with Docker; startup log: {}", service.name(), output);
        commands.run(command, assets, variables, timeout, output);
    }

    static Path extractAssets(Path cacheRoot) {
        try {
            Map<String, byte[]> contents = new LinkedHashMap<>();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String asset : ASSETS) {
                try (var stream = PortableLocalServicesRunner.class.getResourceAsStream("/local-services/" + asset)) {
                    if (stream == null) throw new IllegalStateException("Application is missing bundled service asset: " + asset);
                    byte[] bytes = stream.readAllBytes();
                    contents.put(asset, bytes);
                    digest.update(asset.getBytes(StandardCharsets.UTF_8));
                    digest.update(bytes);
                }
            }
            Path destination = cacheRoot.resolve(HexFormat.of().formatHex(digest.digest()).substring(0, 16));
            for (var entry : contents.entrySet()) {
                Path file = destination.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                if (!Files.isRegularFile(file) || !java.util.Arrays.equals(Files.readAllBytes(file), entry.getValue())) {
                    Files.write(file, entry.getValue());
                }
            }
            return destination;
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot prepare bundled local services", failure);
        }
    }

    interface Commands {
        boolean dockerAvailable();
        void run(List<String> command, Path directory, Map<String, String> environment, Duration timeout, Path output);
    }

    private static final class SystemCommands implements Commands {
        public boolean dockerAvailable() {
            Process process = null;
            try {
                process = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted checking Docker", interrupted);
            } catch (IOException unavailable) { return false; }
            finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        }

        public void run(List<String> command, Path directory, Map<String, String> environment, Duration timeout, Path output) {
            Process process = null;
            try {
                Files.createDirectories(output.getParent());
                ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                        .redirectErrorStream(true).redirectOutput(output.toFile());
                builder.environment().putAll(environment);
                process = builder.start();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Timed out starting bundled local services; see " + output);
                }
                if (process.exitValue() != 0) throw new IllegalStateException("Cannot start bundled local services; see " + output);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot run Docker Compose; see " + output, failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted starting bundled local services", interrupted);
            } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        }
    }
}
