package com.aloute;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.messaging.api.CallHistoryAuthorization;
import com.aloute.messaging.api.CallSignaling;
import com.aloute.messaging.calls.application.CallRegistryService;
import com.aloute.messaging.calls.domain.CallSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Full production composition with only isolated local resources and test-only configuration. */
@SpringBootTest(classes = AlouteApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=optional:classpath:/quality-no-runtime-config.properties",
            "spring.profiles.active=quality-test",
            "jwt.secret=quality-test-jwt-key-for-disposable-context-only",
            "jwt.expiration=3600000",
            "app.security.encryption-key=quality-test-encryption-key-distinct-from-jwt",
            "cloudinary.cloud-name=quality-test", "cloudinary.api-key=quality-test", "cloudinary.api-secret=quality-test",
            "app.mail.from=test@example.invalid", "app.mail.frontend-base-url=http://localhost",
            "spring.mail.host=127.0.0.1", "spring.mail.port=15999",
            "spring.jpa.hibernate.ddl-auto=validate", "spring.datasource.hikari.maximum-pool-size=4",
            "app.websocket.broker-relay.enabled=false", "app.firebase.admin.project-id=",
            "app.host.bootstrap-enabled=false", "app.seed.demo-data=false"
        })
@EnabledIfEnvironmentVariable(named = "ALOUTE_SCHEMA_VALIDATION_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FullBackendContextIT {
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry properties) {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        if (url == null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aloute_quality_[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Refusing non-disposable/local database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD"));
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> System.getenv("ALOUTE_REDIS_VALIDATION_PORT"));
    }

    @Test
    void fullBackendStartsWithDurableCallsAndAllModuleProviders() {
        assertInstanceOf(CallRegistryService.class, context.getBean(CallSignaling.class));
        assertNotNull(context.getBean(CallSessions.class));
        assertNotNull(context.getBean(CallHistoryAuthorization.class));
        assertNotNull(context.getBean(com.aloute.notifications.api.NotificationIntents.class));
        assertNotNull(context.getBean(com.aloute.feed.api.FeedQuery.class));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void logoutRequiresCsrfExpiresCookiesAndRejectsAccessAndRefreshReplayWithoutRevokingOtherSessions() throws Exception {
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        String name = "logout-it-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Long role = jdbc.queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id", Long.class);
        Long viewer = jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,role_id) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, name, name + "@example.invalid", "test-only", name, role);
        var jwt = context.getBean(com.aloute.config.JwtUtil.class);
        var refresh = context.getBean(com.aloute.service.RefreshTokenService.class);
        var tokens = context.getBean(com.aloute.repository.IRefreshTokenRepository.class);
        var hashing = context.getBean(com.aloute.service.AuthTokenService.class);
        String access = jwt.generateToken(name, viewer, "ROLE_USER");
        String rawRefresh = refresh.issue(viewer);
        String otherSession = refresh.issue(viewer);
        var accessCookie = new jakarta.servlet.http.Cookie("ALOUTE_TOKEN", access);
        var refreshCookie = new jakarta.servlet.http.Cookie("ALOUTE_REFRESH", rawRefresh);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup((org.springframework.web.context.WebApplicationContext) context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var page = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/login")
                        .cookie(accessCookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/feed"))
                .andReturn();
        var csrf = page.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/logout")
                        .servletPath("/api/auth/logout").cookie(accessCookie, refreshCookie, csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/logout")
                        .servletPath("/api/auth/logout").cookie(accessCookie, refreshCookie, csrf).header("X-XSRF-TOKEN", "wrong-token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertTrue(jwt.isTokenValid(access, name));
        assertNull(tokens.findByTokenHash(hashing.hashToken(rawRefresh)).orElseThrow().getRevokedAt());
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/logout")
                        .servletPath("/api/auth/logout").cookie(accessCookie, refreshCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true))
                .andReturn().getResponse();
        for (String cookieName : java.util.List.of("ALOUTE_TOKEN", "ALOUTE_REFRESH")) {
            var expired = response.getCookie(cookieName);
            assertNotNull(expired);
            assertEquals(0, expired.getMaxAge());
            assertTrue(expired.isHttpOnly());
            assertEquals(cookieName.equals("ALOUTE_TOKEN") ? "/" : "/api/auth", expired.getPath());
        }
        assertFalse(jwt.isTokenValid(access, name));
        assertNotNull(tokens.findByTokenHash(hashing.hashToken(rawRefresh)).orElseThrow().getRevokedAt());
        assertNull(tokens.findByTokenHash(hashing.hashToken(otherSession)).orElseThrow().getRevokedAt());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/users/me")
                        .servletPath("/api/users/me").cookie(accessCookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/feed").cookie(accessCookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/login").cookie(accessCookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/refresh")
                        .servletPath("/api/auth/refresh").cookie(refreshCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void authenticatedFeedRendersLegacyArraysIsoAndRejectsMalformedDatesWithoutLeakingPrivatePosts() throws Exception {
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        String name = "date-it-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
        Long role = jdbc.queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id",Long.class);
        Long viewer = jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,role_id) VALUES (?,?,?,?,?) RETURNING id",Long.class,
                name,name+"@example.invalid","test-only",name,role);
        Long author = jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,role_id) VALUES (?,?,?,?,?) RETURNING id",Long.class,
                name+"-other",name+"-other@example.invalid","test-only",name+" other",role);
        for (String[] fixture : java.util.List.of(
                new String[]{"array","[2026,10,23,0,21,23,183036801]","PUBLIC"},
                new String[]{"iso","\"2026-10-23T00:21:23.183036800\"","PUBLIC"},
                new String[]{"null","null","PUBLIC"},
                new String[]{"malformed","\"bad-date\"","PUBLIC"},
                new String[]{"private","[2026,10,23,1,21]","ONLY_ME"})) {
            String id = name+"-"+fixture[0];
            jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('posts',?,?::jsonb)",id,
                    "{\"id\":\""+id+"\",\"userId\":\""+author+"\",\"content\":\""+id+"\",\"visibility\":\""+fixture[2]+"\",\"createdAt\":"+fixture[1]
                            +(fixture[0].equals("array")?",\"imageUrl\":\"/images/avatar_default.png\"":"")
                            +(fixture[0].equals("iso")?",\"videoUrl\":\"/test-only-video.mp4\"":"")+"}");
        }
        var user = context.getBean(com.aloute.repository.IUserRepository.class).findById(viewer).orElseThrow();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup((org.springframework.web.context.WebApplicationContext)context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        for (int attempt = 0; attempt < 2; attempt++) { // SQL miss followed by cached page/visibility recheck.
            var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/feed")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(new com.aloute.security.AloutePrincipal(user))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
            String html = result.getResponse().getContentAsString();
            assertTrue(html.contains(name+"-array")); assertTrue(html.contains(name+"-iso"));
            assertFalse(html.contains(name+"-private")); assertFalse(html.contains(name+"-malformed"));
            assertFalse(html.contains(name+"-null"));
            assertTrue(html.contains("fetchpriority=\"high\""));
            assertTrue(html.contains("loading=\"eager\""));
            assertTrue(html.contains("preload=\"none\""));
            var cards = (java.util.List<?>)result.getModelAndView().getModel().get("posts");
            assertEquals(java.util.List.of(name+"-array",name+"-iso"),cards.stream().map(card -> ((com.aloute.dto.response.PostResponse)card).getId()).toList());
        }
        jdbc.update("INSERT INTO friends(follower_id,followed_id) VALUES (?,?)",viewer,author);
        var feed=context.getBean(com.aloute.feed.api.FeedQuery.class);
        var following=feed.getCursorFeedForViewer(viewer,"following",null,null,20);
        assertEquals(java.util.List.of(name+"-array",name+"-iso"),following.items().stream().map(com.aloute.dto.response.PostResponse::getId).toList());
        var firstPage=feed.getCursorFeedForViewer(viewer,"following",null,null,1);
        assertEquals(name+"-array",firstPage.items().getFirst().getId());
        assertTrue(firstPage.hasMore());
        var secondPage=feed.getCursorFeedForViewer(viewer,"following",null,firstPage.nextCursor(),1);
        assertEquals(name+"-iso",secondPage.items().getFirst().getId());
        // Direct SQL intentionally bypasses eviction to prove cache hits use current follow edges.
        jdbc.update("DELETE FROM friends WHERE follower_id=? AND followed_id=?",viewer,author);
        assertTrue(feed.getCursorFeedForViewer(viewer,"following",null,null,20).items().isEmpty());
        assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM app_document_records WHERE collection_name='posts' AND payload->>'userId'=?",Integer.class,author.toString()));
    }
}
