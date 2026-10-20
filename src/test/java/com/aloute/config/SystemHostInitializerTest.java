package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class SystemHostInitializerTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final IRoleRepository roles = mock(IRoleRepository.class);

    @Test
    void promotesOnlyWhenExplicitBootstrapIsEnabled() throws Exception {
        User account = verifiedUser();
        Role host = new Role("ROLE_HOST");
        when(users.findByUsernameIgnoreCase("owner")).thenReturn(Optional.of(account));
        when(roles.findByName("ROLE_HOST")).thenReturn(Optional.of(host));

        new SystemHostInitializer(users, roles, "owner", true).run();

        assertEquals("ROLE_HOST", account.getRole().getName());
        verify(users).save(account);
    }

    @Test
    void disabledBootstrapDoesNotChangeRole() throws Exception {
        User account = verifiedUser();
        when(users.findByUsernameIgnoreCase("owner")).thenReturn(Optional.of(account));

        new SystemHostInitializer(users, roles, "owner", false).run();

        assertEquals("ROLE_USER", account.getRole().getName());
        verify(users, never()).save(account);
    }

    @Test
    void unverifiedAccountCannotBecomeHost() throws Exception {
        User account = verifiedUser();
        account.setEmailVerified(false);
        when(users.findByUsernameIgnoreCase("owner")).thenReturn(Optional.of(account));

        new SystemHostInitializer(users, roles, "owner", true).run();

        assertEquals("ROLE_USER", account.getRole().getName());
        verify(users, never()).save(account);
    }

    private static User verifiedUser() {
        User user = new User();
        user.setUsername("owner");
        user.setActive(true);
        user.setEmailVerified(true);
        user.setRole(new Role("ROLE_USER"));
        return user;
    }
}
