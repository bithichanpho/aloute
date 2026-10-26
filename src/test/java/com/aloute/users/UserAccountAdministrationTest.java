package com.aloute.users;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.common.exception.*;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.aloute.users.infrastructure.JpaUserAccountAdministration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserAccountAdministrationTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final JpaUserAccountAdministration service = new JpaUserAccountAdministration(users);

    @Test
    void ordinaryHostAndInactiveAdminCannotChangeOrReviewAccounts() {
        for (String role : List.of("ROLE_USER", "ROLE_HOST", "ROLE_ADMIN")) {
            User actor = user(9L, role); actor.setActive(!role.equals("ROLE_ADMIN"));
            when(users.findById(9L)).thenReturn(Optional.of(actor));
            assertThrows(ForbiddenException.class, () -> service.setHiddenByAdministrator(9L, 7L, true));
            assertThrows(ForbiddenException.class, () -> service.reviewAccount(9L, 7L));
        }
        assertThrows(ForbiddenException.class, () -> service.requireAdministrator(null));
        verify(users, never()).save(any());
        verify(users, never()).findById(7L);
    }

    @Test
    void adminCannotModerateSelfOrProtectedAdminHost() {
        when(users.findById(9L)).thenReturn(Optional.of(user(9L, "ROLE_ADMIN")));
        assertThrows(BadRequestException.class, () -> service.setHiddenByAdministrator(9L, 9L, true));
        for (String role : List.of("ROLE_ADMIN", "ROLE_HOST")) {
            when(users.findById(7L)).thenReturn(Optional.of(user(7L, role)));
            assertThrows(BadRequestException.class, () -> service.setHiddenByAdministrator(9L, 7L, true));
        }
        verify(users, never()).save(any());
    }

    @Test
    void hideAndApprovePreserveAccountRulesAndReturnOnlyRevocationMetadata() {
        when(users.findById(9L)).thenReturn(Optional.of(user(9L, "ROLE_ADMIN")));
        User target = user(7L, "ROLE_USER"); target.setSelfDeactivated(true);
        when(users.findById(7L)).thenReturn(Optional.of(target));
        var changed = service.setHiddenByAdministrator(9L, 7L, true);
        assertFalse(target.isActive());
        assertFalse(Boolean.TRUE.equals(target.getSelfDeactivated()));
        assertEquals(target.getTokensRevokedAt(), changed.revokedAt());
        assertEquals(target.getUsername(), changed.username());
        assertEquals(7L, changed.id());
        service.setHiddenByAdministrator(9L, 7L, false);
        assertTrue(target.isActive());
        verify(users, times(2)).save(target);
    }

    @Test
    void reviewProjectionCannotExposePasswordEmailOrTokenFields() {
        when(users.findById(9L)).thenReturn(Optional.of(user(9L, "ROLE_ADMIN")));
        User target = user(7L, "ROLE_USER");
        target.setPassword("dummy-password"); target.setEmail("test@example.invalid");
        when(users.findById(7L)).thenReturn(Optional.of(target));
        var review = service.reviewAccount(9L, 7L);
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(review);
        assertFalse(json.contains("password"));
        assertFalse(json.contains("email"));
        assertFalse(json.contains("tokensRevokedAt"));
    }

    private User user(Long id, String role) {
        User user = new User(); user.setId(id); user.setUsername("user" + id);
        user.setFullName("User " + id); user.setRole(new Role(role)); user.setActive(true);
        return user;
    }
}
