package com.aloute.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI alouteOpenApi() {
        Components components = new Components()
                .addSecuritySchemes("accessCookie", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name(JwtAuthFilter.COOKIE_NAME)
                        .description("HttpOnly access cookie issued by ALOUTE login."))
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
        return new OpenAPI()
                .info(new Info()
                        .title("ALOUTE API")
                        .version("1.0")
                        .description("API cho mạng xã hội ALOUTE. Nội dung, quyền truy cập và ví dụ trong tài liệu không thay thế chính sách bảo mật của hệ thống.")
                        .contact(new Contact().name("ALOUTE Engineering")))
                .components(components);
    }
}
