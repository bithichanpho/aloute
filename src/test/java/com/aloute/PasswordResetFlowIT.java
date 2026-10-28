package com.aloute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aloute.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes=AlouteApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.location=optional:classpath:/quality-no-runtime-config.properties", "spring.profiles.active=quality-test",
    "jwt.secret=quality-test-jwt-key-for-disposable-context-only", "jwt.expiration=3600000",
    "app.security.encryption-key=quality-test-encryption-key-distinct-from-jwt",
    "cloudinary.cloud-name=quality-test","cloudinary.api-key=quality-test","cloudinary.api-secret=quality-test",
    "app.mail.from=test@example.invalid","app.mail.frontend-base-url=http://localhost","spring.mail.host=127.0.0.1","spring.mail.port=15999",
    "spring.jpa.hibernate.ddl-auto=validate","spring.datasource.hikari.maximum-pool-size=4",
    "app.websocket.broker-relay.enabled=false","app.firebase.admin.project-id=","app.host.bootstrap-enabled=false","app.seed.demo-data=false"
})
@EnabledIfEnvironmentVariable(named="ALOUTE_SCHEMA_VALIDATION_URL",matches=".+")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetFlowIT {
    @Autowired ApplicationContext context;
    @MockitoBean EmailService mail;
    @DynamicPropertySource static void resources(DynamicPropertyRegistry properties) {
        String url=System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aloute_quality_[A-Za-z0-9_]+"))throw new IllegalArgumentException("Test DB only");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"));
        properties.add("spring.datasource.password",()->System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD"));
        properties.add("spring.data.redis.host",()->"127.0.0.1");
        properties.add("spring.data.redis.port",()->System.getenv("ALOUTE_REDIS_VALIDATION_PORT"));
    }
    private final java.util.List<Long> owned=new java.util.ArrayList<>();
    private org.springframework.jdbc.core.JdbcTemplate jdbc(){return context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);}
    private String email;
    private Long fixture(){
        String name="reset-it-"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,14);
        email=name+"@example.invalid";
        Long role=jdbc().queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id",Long.class);
        Long id=jdbc().queryForObject("INSERT INTO users(username,email,password,full_name,role_id,reset_password_token,reset_password_expiry) VALUES (?,?,?,?,?,?,?) RETURNING id",Long.class,
                name,email,context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).encode("Old-Pass1!"),name,role,
                context.getBean(com.aloute.service.AuthTokenService.class).hashOtp(email,"012345"),java.time.LocalDateTime.now().plusMinutes(8));
        owned.add(id);return id;
    }
    private org.springframework.test.web.servlet.MockMvc mvc(){return org.springframework.test.web.servlet.setup.MockMvcBuilders
            .webAppContextSetup((org.springframework.web.context.WebApplicationContext)context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();}
    @org.junit.jupiter.api.AfterEach void cleanup(){for(Long id:owned)jdbc().update("DELETE FROM users WHERE id=? AND username LIKE 'reset-it-%'",id);}

    @Test void twoStepHttpRequiresCsrfAndVerifiedCookieAndRevokesSessions() throws Exception {
        Long id=fixture();var mvc=mvc();
        mvc.perform(get("/reset-password")).andExpect(redirectedUrl("/verify-reset-code"));
        var codePage=mvc.perform(get("/verify-reset-code")).andExpect(status().isOk()).andReturn().getResponse();
        assertFalse(codePage.getContentAsString().contains("type=\"password\""));
        var csrf=codePage.getCookie("XSRF-TOKEN");assertNotNull(csrf);
        String body="{\"email\":\""+email+"\",\"code\":\"012345\"}";
        mvc.perform(post("/api/auth/verify-reset-code").servletPath("/api/auth/verify-reset-code").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/verify-reset-code").servletPath("/api/auth/verify-reset-code").cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(body.replace("012345","1111111"))).andExpect(status().isBadRequest())
                .andExpect(cookie().doesNotExist(com.aloute.controller.PasswordResetController.COOKIE));
        var result=mvc.perform(post("/api/auth/verify-reset-code").servletPath("/api/auth/verify-reset-code").cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty()).andReturn().getResponse();
        var proof=result.getCookie(com.aloute.controller.PasswordResetController.COOKIE);assertNotNull(proof);assertTrue(proof.isHttpOnly());assertEquals(300,proof.getMaxAge());
        mvc.perform(get("/reset-password").cookie(proof)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"code\""))));
        var encoder=context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
        assertTrue(encoder.matches("Old-Pass1!",jdbc().queryForObject("SELECT password FROM users WHERE id=?",String.class,id)));
        String refresh=context.getBean(com.aloute.service.RefreshTokenService.class).issue(id);
        String next="{\"newPassword\":\"New-Pass1!\"}";
        mvc.perform(post("/api/auth/complete-password-reset").servletPath("/api/auth/complete-password-reset").cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(next)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/complete-password-reset").servletPath("/api/auth/complete-password-reset").cookie(proof)
                .contentType("application/json").content(next)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/complete-password-reset").servletPath("/api/auth/complete-password-reset").cookie(proof,csrf).header("X-XSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(next)).andExpect(status().isOk()).andExpect(cookie().maxAge(proof.getName(),0));
        assertTrue(encoder.matches("New-Pass1!",jdbc().queryForObject("SELECT password FROM users WHERE id=?",String.class,id)));
        assertNull(jdbc().queryForObject("SELECT reset_password_token FROM users WHERE id=?",String.class,id));
        assertNotNull(context.getBean(com.aloute.repository.IRefreshTokenRepository.class).findByTokenHash(context.getBean(com.aloute.service.AuthTokenService.class).hashToken(refresh)).orElseThrow().getRevokedAt());
        mvc.perform(get("/reset-password").cookie(proof)).andExpect(redirectedUrl("/verify-reset-code"));
        mvc.perform(post("/api/auth/complete-password-reset").servletPath("/api/auth/complete-password-reset").cookie(proof,csrf).header("X-XSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(next)).andExpect(status().isBadRequest());
    }
    @Test void replacementAndExpiryInvalidatePreviouslyVerifiedProof() {
        Long id=fixture();var flow=context.getBean(com.aloute.service.PasswordResetFlowService.class);
        String proof=flow.verify(email,"012345");
        jdbc().update("UPDATE users SET reset_password_token=? WHERE id=?","f".repeat(64),id);
        assertTrue(flow.verifiedEmail(proof).isEmpty());assertThrows(com.aloute.common.exception.BadRequestException.class,()->flow.complete(proof,"New-Pass1!"));
        jdbc().update("UPDATE users SET reset_password_token=?,reset_password_expiry=? WHERE id=?",context.getBean(com.aloute.service.AuthTokenService.class).hashOtp(email,"012345"),java.time.LocalDateTime.now().minusSeconds(1),id);
        assertTrue(flow.verifiedEmail(proof).isEmpty());
    }
    @Test void repeatingOtpInNewIssuanceCannotReactivateAnOldProofOrWinItsCas() {
        Long id=fixture();var flow=context.getBean(com.aloute.service.PasswordResetFlowService.class);
        String proof=flow.verify(email,"012345");
        var previousExpiry=jdbc().queryForObject("SELECT reset_password_expiry FROM users WHERE id=?",java.time.LocalDateTime.class,id);
        jdbc().update("UPDATE users SET reset_password_expiry=reset_password_expiry+interval '1 minute' WHERE id=?",id);
        assertTrue(flow.verifiedEmail(proof).isEmpty());assertThrows(com.aloute.common.exception.BadRequestException.class,()->flow.complete(proof,"New-Pass1!"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        int consumed=tx.execute(s->context.getBean(com.aloute.repository.IUserRepository.class).consumeVerifiedResetToken(id,
                context.getBean(com.aloute.service.AuthTokenService.class).hashOtp(email,"012345"),previousExpiry,"should-not-write",
                java.time.Instant.now(),java.time.LocalDateTime.now()));
        assertEquals(0,consumed);assertNotEquals("should-not-write",jdbc().queryForObject("SELECT password FROM users WHERE id=?",String.class,id));
        assertTrue(flow.verifiedEmail(flow.verify(email,"012345")).isPresent());
    }
    @Test void encryptedHttpOnlyCookieSurvivesRealHttpRoundTripAndRejectsTampering() throws Exception {
        fixture();
        int port=context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
        String origin="http://127.0.0.1:"+port;
        var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var page=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/verify-reset-code")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,page.statusCode());
        String csrf=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
        String body="{\"email\":\""+email+"\",\"code\":\"012345\"}";
        var verified=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/auth/verify-reset-code"))
                .header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,verified.statusCode());
        var proof=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals(com.aloute.controller.PasswordResetController.COOKIE)).findFirst().orElseThrow();
        assertTrue(proof.isHttpOnly());
        var passwordPage=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/reset-password")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,passwordPage.statusCode());assertTrue(passwordPage.body().contains("id=\"newPassword\""));
        proof.setValue("tampered");
        var rejected=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/reset-password")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(302,rejected.statusCode());
        assertEquals(java.net.URI.create(origin+"/verify-reset-code"),java.net.URI.create(origin).resolve(rejected.headers().firstValue("Location").orElseThrow()));
    }
    @Test void concurrentCompletionsConsumeExactlyOnce() throws Exception {
        Long id=fixture();var flow=context.getBean(com.aloute.service.PasswordResetFlowService.class);String proof=flow.verify(email,"012345");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try{flow.complete(proof,"New-Pass1!");return true;}catch(com.aloute.common.exception.BadRequestException expected){return false;}}));
            start.countDown();int wins=0;for(var task:tasks)if(task.get(30,java.util.concurrent.TimeUnit.SECONDS))wins++;
            assertEquals(1,wins);assertNull(jdbc().queryForObject("SELECT reset_password_token FROM users WHERE id=?",String.class,id));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void atomicResetIssuanceDoesNotOverwritePasswordAndOnlyOneSenderWins() {
        Long id=fixture();jdbc().update("UPDATE users SET reset_password_expiry=null,reset_password_token=null WHERE id=?",id);
        var users=context.getBean(com.aloute.repository.IUserRepository.class);var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var stale=users.findById(id).orElseThrow();
        jdbc().update("UPDATE users SET password='newer-password-state' WHERE id=?",id);
        var now=java.time.LocalDateTime.now();
        assertEquals(1,tx.execute(s->users.issueResetToken(stale.getId(),"b".repeat(64),now.plusMinutes(10),now.plusMinutes(9))).intValue());
        assertEquals(0,tx.execute(s->users.issueResetToken(stale.getId(),"c".repeat(64),now.plusMinutes(10),now.plusMinutes(9))).intValue());
        assertEquals("newer-password-state",jdbc().queryForObject("SELECT password FROM users WHERE id=?",String.class,id));
    }
}
