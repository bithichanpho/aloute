package com.aloute.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** Start local services after config loading, before application beans/workers. */
public final class LocalServicesAutoStarter
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private static final Logger log = LoggerFactory.getLogger(LocalServicesAutoStarter.class);
    private final String osName;
    private final Path projectRoot;
    private final ServiceRunner runner;

    public LocalServicesAutoStarter() {
        this(System.getProperty("os.name", ""), Path.of(System.getProperty("user.dir")),
                new PortableLocalServicesRunner(System.getProperty("os.name", ""),
                        Path.of(System.getProperty("user.home"), ".aloute", "local-services")));
    }

    LocalServicesAutoStarter(String osName, Path projectRoot, ServiceRunner runner) {
        this.osName = osName;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.runner = runner;
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        start(event.getEnvironment());
    }

    void start(Environment environment) {
        if (List.of("windows", "linux", "mac").stream()
                        .noneMatch(osName.toLowerCase(Locale.ROOT)::startsWith)
                || !environment.getProperty("app.local-services.auto-start", Boolean.class, true)
                || environment.getProperty("spring.docker.compose.enabled", Boolean.class, false)
                || Arrays.stream(environment.getActiveProfiles())
                        .anyMatch(Set.of("prod", "production", "call-distributed", "test")::contains)) return;

        List<LocalService> services = new ArrayList<>();
        if (environment.getProperty("app.cache.redis.enabled", Boolean.class, true)
                || environment.getProperty("app.security.rate-limit.redis-required", Boolean.class, false)) {
            URI redis = URI.create(environment.getProperty("spring.data.redis.url", "redis://localhost:6379"));
            if (isLocal(redis, "redis", 6379) && redis.getUserInfo() == null) {
                services.add(new LocalService("Redis", redis, "start-local-redis.ps1"));
            }
        }
        if (environment.getProperty("app.moderation.enabled", Boolean.class, true)) {
            URI moderation = URI.create(environment.getProperty("app.moderation.service-url", "http://localhost:8000"));
            if (isLocal(moderation, "http", 8000) && moderation.getUserInfo() == null
                    && (moderation.getPath() == null || Set.of("", "/").contains(moderation.getPath()))) {
                services.add(new LocalService("Moderation", moderation, "start-local-moderation.ps1"));
            }
        }
        if (services.isEmpty()) return;
        Duration timeout = environment.getProperty("app.local-services.startup-timeout", Duration.class, Duration.ofMinutes(5));
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalStateException("Local service startup timeout must be positive");
        runner.configure(environment);
        for (LocalService service : services) {
            if (runner.isReady(service)) {
                log.info("Local {} is ready", service.name());
                continue;
            }
            Path helper = projectRoot.resolve("deploy").resolve(service.helper());
            log.info("Starting local {} before application startup", service.name());
            runner.start(service, helper, timeout);
            if (!runner.isReady(service)) {
                throw new IllegalStateException("Local " + service.name() + " did not become ready; inspect the local service startup log");
            }
            log.info("Local {} is ready", service.name());
        }
    }

    private static boolean isLocal(URI uri, String scheme, int port) {
        return scheme.equalsIgnoreCase(uri.getScheme()) && uri.getPort() == port
                && uri.getHost() != null
                && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    record LocalService(String name, URI endpoint, String helper) {}

    interface ServiceRunner {
        default void configure(Environment environment) {}
        boolean isReady(LocalService service);
        void start(LocalService service, Path helper, Duration timeout);
    }

    static final class WorkstationRunner implements ServiceRunner {
        public boolean isReady(LocalService service) {
            try {
                if ("Redis".equals(service.name())) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(service.endpoint().getHost(), service.endpoint().getPort()), 1500);
                        socket.setSoTimeout(1500);
                        socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
                        return "+PONG\r\n".equals(new String(socket.getInputStream().readNBytes(7), StandardCharsets.US_ASCII));
                    }
                }
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                    HttpRequest request = HttpRequest.newBuilder(service.endpoint().resolve("/healthz"))
                            .timeout(Duration.ofSeconds(3)).GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) return false;
                    var health = JsonMapper.builder().build().readTree(response.body());
                    return "ok".equals(health.path("status").asText())
                            && "moderation-api".equals(health.path("provider").asText());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted checking local services", interrupted);
            } catch (Exception unavailable) { return false; }
        }

        public void start(LocalService service, Path helper, Duration timeout) {
            if (!Files.isRegularFile(helper)) {
                throw new IllegalStateException("Cannot start local " + service.name()
                        + ": install and start Docker, or run from the project directory containing deploy/" + service.helper()
                        + ". Set LOCAL_SERVICES_AUTO_START=false to manage services yourself.");
            }
            Path root = helper.getParent().getParent();
            Path output = root.resolve("target/local-services-" + service.name().toLowerCase(Locale.ROOT) + ".log");
            Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            Process process = null;
            try {
                Files.createDirectories(output.getParent());
                process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                        "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", helper.toString())
                        .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Timed out starting local " + service.name() + "; see " + output);
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("Cannot start local " + service.name() + "; see " + output
                            + ". First-time setup instructions are in moderation/README_LOCAL_VI.md.");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot launch local " + service.name() + " helper; see " + output, failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted starting local " + service.name(), interrupted);
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
