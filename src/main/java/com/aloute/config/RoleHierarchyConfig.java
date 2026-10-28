package com.aloute.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authorization.AuthorizationManagerFactory;
import org.springframework.security.authorization.DefaultAuthorizationManagerFactory;

/**
 * System-wide role inheritance. A HOST is a super-administrator: it inherits ADMIN and USER
 * capabilities. An ADMIN inherits ordinary USER capabilities. HOST-only endpoints must still
 * explicitly require HOST so the inheritance is one-way.
 *
 * Spring Security 7 uses AuthorizationManagerFactory for both request- and method-based
 * authorization; wiring the hierarchy into this factory keeps request matchers and @PreAuthorize
 * decisions consistent.
 */
@Configuration
public class RoleHierarchyConfig {
    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(
                "ROLE_HOST > ROLE_ADMIN\nROLE_ADMIN > ROLE_USER");
    }

    @Bean
    static <T> AuthorizationManagerFactory<T> authorizationManagerFactory(RoleHierarchy roleHierarchy) {
        DefaultAuthorizationManagerFactory<T> factory = new DefaultAuthorizationManagerFactory<>();
        factory.setRoleHierarchy(roleHierarchy);
        factory.setRolePrefix("ROLE_");
        return factory;
    }
}
