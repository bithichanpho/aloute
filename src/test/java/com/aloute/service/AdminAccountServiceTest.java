package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.ForbiddenException;
import com.aloute.model.entity.AccountAdminAudit;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IAccountAdminAuditRepository;
import com.aloute.repository.IUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class AdminAccountServiceTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final IAccountAdminAuditRepository audits = mock(IAccountAdminAuditRepository.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AuthenticationUserCache authenticationCache = mock(AuthenticationUserCache.class);
    private final AdminAccountService service =
            new AdminAccountService(users, audits, refreshTokens, authenticationCache);

    @Test
    void lockDisablesAccountRevokesEverySessionAndWritesAudit() {
        User target = user(7L, "member", "ROLE_USER");
        when(users.findById(7L)).thenReturn(Optional.of(target));

        var result = service.lock(1L, 7L, "Vi phạm tiêu chuẩn cộng đồng");

        assertFalse(result.active());
        verify(refreshTokens).revokeAll(org.mockito.ArgumentMatchers.eq(7L), any());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat((AccountAdminAudit audit) ->
                "LOCK".equals(audit.getAction())
                        && audit.getReason().contains("Vi phạm")
                        && audit.getActorId().equals(1L)));
    }

    @Test
    void standardAdminCannotLockHostOrAnotherAdmin() {
        when(users.findById(8L)).thenReturn(Optional.of(user(8L, "host", "ROLE_HOST")));
        assertThrows(ForbiddenException.class,
                () -> service.lock(1L, 8L, "Không được phép thao tác"));
    }

    @Test
    void listUsesDatabasePaginationAndReturnsMetadata() {
        User target = user(7L, "member", "ROLE_USER");
        when(users.findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                org.mockito.ArgumentMatchers.eq("mem"), org.mockito.ArgumentMatchers.eq("mem"),
                org.mockito.ArgumentMatchers.eq("mem"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(target),
                        org.springframework.data.domain.PageRequest.of(2, 10), 35));

        var result = service.list("mem", 2, 10);

        assertEquals(1, result.items().size());
        assertEquals(35, result.totalElements());
        assertEquals(4, result.totalPages());
    }

    private User user(Long id, String username, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        return user;
    }
}
