package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class RoleHierarchyConfigTest {
    private final RoleHierarchy hierarchy = RoleHierarchyConfig.roleHierarchy();

    @Test
    void hostInheritsAdminAndUserPermissions() {
        Set<String> roles = hierarchy.getReachableGrantedAuthorities(
                Set.of(new SimpleGrantedAuthority("ROLE_HOST")))
                .stream().map(a -> a.getAuthority()).collect(Collectors.toSet());

        assertTrue(roles.contains("ROLE_HOST"));
        assertTrue(roles.contains("ROLE_ADMIN"));
        assertTrue(roles.contains("ROLE_USER"));
    }

    @Test
    void adminInheritsUserButNotHostPermissions() {
        Set<String> roles = hierarchy.getReachableGrantedAuthorities(
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .stream().map(a -> a.getAuthority()).collect(Collectors.toSet());

        assertTrue(roles.contains("ROLE_ADMIN"));
        assertTrue(roles.contains("ROLE_USER"));
        assertFalse(roles.contains("ROLE_HOST"));
    }
    @Test
    void userDoesNotInheritAdministrativePermissions() {
        Set<String> roles = hierarchy.getReachableGrantedAuthorities(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")))
                .stream().map(a -> a.getAuthority()).collect(Collectors.toSet());

        assertTrue(roles.contains("ROLE_USER"));
        assertFalse(roles.contains("ROLE_ADMIN"));
        assertFalse(roles.contains("ROLE_HOST"));
    }

}

