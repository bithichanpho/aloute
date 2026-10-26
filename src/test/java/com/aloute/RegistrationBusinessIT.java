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
class RegistrationBusinessIT {
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
    @Test @Transactional void realRegisterAcceptsLongEmailAndUnicodeWithoutOverwritingPendingIdentity() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext)context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var cookie=mvc.perform(get("/register")).andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        String name="reg-"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,12);
        String email=name+"@"+"a".repeat(60)+".another-label-with-more-than-forty-characters.example.invalid";
        var json=JsonMapper.builder().build();
        String body=json.writeValueAsString(java.util.Map.of("fullName","Test Owner","username",name,"email",email,"password","MậtKhẩu١!"));
        var first=mvc.perform(post("/api/auth/register").servletPath("/api/auth/register").cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue())
            .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        var em=context.getBean(jakarta.persistence.EntityManager.class); em.flush();
        var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        String encoded=jdbc.queryForObject("SELECT password FROM pending_users WHERE email=?",String.class,email);
        assertTrue(context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).matches("MậtKhẩu١!",encoded));
        String retry=json.writeValueAsString(java.util.Map.of("fullName","Changed Owner","username",name+"new","email",email,"password","Other-Password1!"));
        var second=mvc.perform(post("/api/auth/register").servletPath("/api/auth/register").cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue())
            .contentType("application/json").content(retry)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        em.flush();
        assertEquals(name,jdbc.queryForObject("SELECT username FROM pending_users WHERE email=?",String.class,email));
        assertEquals(encoded,jdbc.queryForObject("SELECT password FROM pending_users WHERE email=?",String.class,email));
        assertEquals(json.readTree(first.getResponse().getContentAsString()).get("message"),json.readTree(second.getResponse().getContentAsString()).get("message"));
        verify(mail,times(1)).sendVerificationEmail(eq(email),eq("Test Owner"),anyString());
        String overflow=json.writeValueAsString(java.util.Map.of("fullName","Test","username",name+"bad","email",name+"bad@example.invalid","password","Aa1!"+"ấ".repeat(24)));
        mvc.perform(post("/api/auth/register").servletPath("/api/auth/register").cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue())
            .contentType("application/json").content(overflow)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pending_users WHERE username=?",Integer.class,name+"bad"));
    }
}
