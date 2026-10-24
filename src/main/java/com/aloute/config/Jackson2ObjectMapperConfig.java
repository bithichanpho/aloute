package com.aloute.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the Jackson 2 ObjectMapper used by the PostgreSQL JSONB persistence
 * adapter. Spring Boot 4 auto-configures Jackson 3 (tools.jackson) by default,
 * which is a different type from com.fasterxml.jackson.databind.ObjectMapper.
 */
@Configuration(proxyBeanMethods = false)
public class Jackson2ObjectMapperConfig {

    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        // Register Jackson 2 modules available on the runtime classpath
        // (for example Java time support), without replacing Boot's Jackson 3 setup.
        return new ObjectMapper().findAndRegisterModules();
    }
}
