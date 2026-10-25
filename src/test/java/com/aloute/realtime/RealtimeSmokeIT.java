package com.aloute.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.config.*;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;

/** Browser fixture only: real production websocket config/guard and app.js, no production credentials. */
class RealtimeSmokeIT {
    @Test
    void pinnedScriptsConnectReconnectAndIsolateUserDestinationsUnderActualDefaultCsp() throws Exception {
        Assumptions.assumeTrue(System.getenv("ALOUTE_BROWSER_EXECUTABLE") != null
                && System.getenv("ALOUTE_PLAYWRIGHT_MODULE") != null, "Browser runtime not configured");
        SpringApplication application = new SpringApplication(Fixture.class);
        try (var context = (ServletWebServerApplicationContext) application.run(
                "--spring.config.location=optional:classpath:/quality-smoke-no-config.properties",
                "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.cors.allowed-origins=http://127.0.0.1:*")) {
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            Process process = new ProcessBuilder("node", Path.of("src/test/browser/realtime-smoke.cjs").toAbsolutePath().toString(), base)
                    .redirectErrorStream(true).redirectOutput(Path.of("target/quality-browser-smoke.log").toFile()).start();
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "browser timed out");
                assertEquals(0, process.exitValue(), "see target/quality-browser-smoke.log");
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    @TestConfiguration
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({WebSocketConfig.class, Routes.class})
    static class Fixture {
        @Bean JwtUtil jwtUtil() {
            JwtUtil jwt=mock(JwtUtil.class);
            for (String name:List.of("one","two")) {
                when(jwt.extractUsername("smoke-"+name)).thenReturn(name);
                when(jwt.isTokenValid("smoke-"+name,name)).thenReturn(true);
                when(jwt.issuedAt("smoke-"+name)).thenReturn(Instant.now().minusSeconds(1));
                when(jwt.expiresAt("smoke-"+name)).thenReturn(Instant.now().plusSeconds(300));
            }
            return jwt;
        }
        @Bean AccountWebSocketGuard guard() {
            IUserRepository users=mock(IUserRepository.class);
            for(String name:List.of("one","two")) {
                User user=new User();user.setId(name.equals("one")?1L:2L);user.setUsername(name);user.setActive(true);
                when(users.findByUsername(name)).thenReturn(Optional.of(user));
            }
            return new AccountWebSocketGuard(users);
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class Routes {
        private final SimpMessagingTemplate messages;
        Routes(SimpMessagingTemplate messages) {this.messages=messages;}
        @GetMapping(value={"/smoke","/anonymous","/login"},produces="text/html")
        String page(@RequestParam(defaultValue="one") String as, jakarta.servlet.http.HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            if(!Set.of("one","two").contains(as)) throw new IllegalArgumentException();
            if(request.getRequestURI().equals("/smoke")) {
                Cookie cookie=new Cookie(JwtAuthFilter.COOKIE_NAME,"smoke-"+as);cookie.setHttpOnly(true);cookie.setPath("/");response.addCookie(cookie);
            }
            String annotation=SecurityConfig.class.getDeclaredField("contentSecurityPolicy").getAnnotation(Value.class).value();
            String policy=annotation.substring(annotation.indexOf(':')+1,annotation.length()-1);
            SecurityConfig security=new SecurityConfig(mock(JwtAuthFilter.class),mock(org.springframework.web.cors.CorsConfigurationSource.class));
            ReflectionTestUtils.setField(security,"contentSecurityPolicy",policy);
            response.setHeader("Content-Security-Policy",ReflectionTestUtils.invokeMethod(security,"firebaseCompatibleCsp"));
            return "<!doctype html><html><head><script defer src='/js/app.js'></script></head><body data-current-user-id='"
                    +(request.getRequestURI().equals("/login")?"":as.equals("one")?"1":"2")+"'></body></html>";
        }
        @GetMapping("/smoke/send") String send(@RequestParam String to,@RequestParam String id) {
            if(!Set.of("one","two").contains(to)) throw new IllegalArgumentException();
            messages.convertAndSendToUser(to,"/queue/call",Map.of("type","hangup","callId",id,"missed",false));return "ok";
        }
        @GetMapping(value="/api/**",produces="application/json") String api() {return "{\"success\":true,\"data\":0}";}
        @PostMapping("/api/auth/refresh") void refresh(HttpServletResponse response) {response.setStatus(401);}
    }
}
