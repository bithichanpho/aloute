package com.aloute;

import static org.junit.jupiter.api.Assertions.*;
import com.aloute.config.JwtUtil;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** Actual HTTP/JWT/DB/Redis, opt-in synthetic DB only. These are after-only lab numbers. */
@SpringBootTest(classes=AlouteApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.location=optional:classpath:/performance-no-runtime-config.properties","spring.profiles.active=quality-test",
    "jwt.secret=quality-test-jwt-key-for-disposable-context-only","jwt.expiration=3600000",
    "app.security.encryption-key=quality-test-encryption-key-distinct-from-jwt",
    "cloudinary.cloud-name=quality-test","cloudinary.api-key=quality-test","cloudinary.api-secret=quality-test",
    "app.mail.from=test@example.invalid","app.mail.frontend-base-url=http://localhost","spring.mail.host=127.0.0.1","spring.mail.port=15999",
    "spring.jpa.hibernate.ddl-auto=validate","spring.datasource.hikari.maximum-pool-size=4","spring.jpa.open-in-view=false",
    "app.websocket.broker-relay.enabled=false","app.firebase.admin.project-id=","app.host.bootstrap-enabled=false","app.seed.demo-data=false",
    "app.cache.redis.enabled=false","spring.thymeleaf.cache=true","server.compression.enabled=true","server.compression.min-response-size=1024"
})
@EnabledIfEnvironmentVariable(named="ALOUTE_PERFORMANCE_HTTP",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceHttpIT {
    @Value("${local.server.port}") int port;
    @Autowired JwtUtil jwt;
    @Autowired org.springframework.cache.CacheManager caches;
    @DynamicPropertySource static void resources(DynamicPropertyRegistry properties) {
        String url=System.getenv("ALOUTE_PERFORMANCE_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aloute_quality_performance_[A-Za-z0-9_]+"))throw new IllegalArgumentException("Dedicated synthetic DB only");
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->"aloute_test");properties.add("spring.datasource.password",()->"test-only");
        properties.add("spring.data.redis.host",()->"127.0.0.1");properties.add("spring.data.redis.port",()->"56379");
    }
    @Test void measureActualHttpWithoutCallingTheseBeforeAfterImprovements() throws Exception {
        String token=jwt.generateToken("perf-1",1L,"ROLE_USER");
        boolean concurrencyOnly="true".equals(System.getenv("ALOUTE_PERFORMANCE_CONCURRENCY_ONLY"));
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var results=new LinkedHashMap<String,Object>();results.put("scope",concurrencyOnly
                ? "after-only real HTTP, 8 JWT principals, 8 workers, 80 requests; PostgreSQL/local cache, desktop loopback; not production SLO proof"
                : "after-only real HTTP, JWT, PostgreSQL; local cache; desktop loopback; 20 samples per serial route, not production SLO proof");
        for(String path:concurrencyOnly ? List.<String>of() : List.of("/api/posts/feed/cursor?tab=following&size=20&cold=1","/api/posts/feed/cursor?tab=following&size=20","/api/posts/feed/cursor?tab=foryou&size=20&cold=1","/api/posts/feed/cursor?tab=foryou&size=20",
                "/api/users/10","/api/posts/perf-00040000","/api/search/users?q=perf-123&size=20","/api/notifications","/api/chat/conversations","/feed")) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).build();
            for(int i=0;i<3;i++)assertEquals(200,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),path);
            var times=new ArrayList<Double>();long bytes=0;int errors=0;
            for(int i=0;i<20;i++) {
                if(path.contains("cold=1"))caches.getCache("feedPages").evict(com.aloute.service.FeedCacheKey.createForViewer(1L,path.contains("tab=following")?"following":"foryou",null,null,20));
                long start=System.nanoTime();var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());times.add((System.nanoTime()-start)/1e6);
                if(response.statusCode()!=200)errors++;bytes+=response.body().length;
            }
            Collections.sort(times);var metrics=new LinkedHashMap<String,Object>();metrics.put("p50Ms",times.get(9));metrics.put("p95Ms",times.get(18));metrics.put("p99Ms",times.get(19));
            metrics.put("errorRate",errors/20.0);metrics.put("meanResponseBytes",bytes/20);results.put(path,metrics);
            assertEquals(0,errors,path);
        }
        var requests=new ArrayList<HttpRequest>();
        for(int viewer=1;viewer<=8;viewer++) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/posts/feed/cursor?tab=foryou&size=20"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+jwt.generateToken("perf-"+viewer,(long)viewer,"ROLE_USER")).build();
            assertEquals(200,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());requests.add(request);
        }
        var workers=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            long start=System.nanoTime();var tasks=new ArrayList<java.util.concurrent.Future<Double>>();
            for(int i=0;i<80;i++) {
                var request=requests.get(i%8);
                tasks.add(workers.submit(()->{long t=System.nanoTime();assertEquals(200,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());return (System.nanoTime()-t)/1e6;}));
            }
            var times=new ArrayList<Double>();for(var task:tasks)times.add(task.get(60,java.util.concurrent.TimeUnit.SECONDS));
            double seconds=(System.nanoTime()-start)/1e9;Collections.sort(times);
            results.put("http-feed-8-users-8-workers",Map.of("requests",80,"errors",0,"p50Ms",times.get(39),"p95Ms",times.get(75),"p99Ms",times.get(79),"throughputPerSecond",80/seconds));
        } finally {workers.shutdownNow();}
        results.put("localFeedCacheStats",((org.springframework.cache.caffeine.CaffeineCache)caches.getCache("feedPages")).getNativeCache().stats().toString());
        Files.createDirectories(Path.of("target/performance"));
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(concurrencyOnly?"target/performance/http-load-after.json":"target/performance/http-after.json").toFile(),results);
        if(!concurrencyOnly && System.getenv("ALOUTE_BROWSER_EXECUTABLE")!=null) {
            var process=new ProcessBuilder("node","src/test/browser/performance-page.cjs","http://127.0.0.1:"+port)
                    .redirectErrorStream(true).redirectOutput(Path.of("target/performance/browser-page.log").toFile());
            process.environment().put("ALOUTE_PERFORMANCE_ACCESS_TOKEN",token); // Synthetic token, never printed or saved.
            var child=process.start();assertTrue(child.waitFor(60,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        }
    }
}
