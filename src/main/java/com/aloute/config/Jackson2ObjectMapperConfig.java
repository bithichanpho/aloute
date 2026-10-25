package com.aloute.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
        // Dates must be stored as ISO-8601 strings: SQL casts payload ->> 'createdAt' to
        // timestamp/timestamptz, and Jackson's default array/number form cannot be cast.
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
