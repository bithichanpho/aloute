package com.aloute.users.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JpaUserAccountAdministrationTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final JpaUserAccountAdministration administration = new JpaUserAccountAdministration(users);
    private final User host = account(1L, "ROLE_HOST");
    private final User admin = account(2L, "ROLE_ADMIN");
    private final User user = account(3L, "ROLE_USER");
    private final User anotherAdmin = account(4L, "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        Map<Long, User> all = Map.of(1L, host, 2L, admin, 3L, user, 4L, anotherAdmin);
        when(users.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(all.get(invocation.getArgument(0))));
    }

    @Test
    void hostAndAdminMayAccessModerationButUserMayNot() {
        assertDoesNotThrow(() -> administration.requireAdministrator(host.getId()));
        assertDoesNotThrow(() -> administration.requireAdministrator(admin.getId()));
        assertThrows(ForbiddenException.class, () -> administration.requireAdministrator(user.getId()));
    }

    @Test
    void hostCanModerateAnAdminAccountButAdminCannot() {
        assertDoesNotThrow(() -> administration.setHiddenByAdministrator(host.getId(), anotherAdmin.getId(), true));
        assertThrows(BadRequestException.class, () -> administration.setHiddenByAdministrator(admin.getId(), anotherAdmin.getId(), true));
    }

    @Test
    void ordinaryAdminCanModerateUserButHostAccountRemainsProtected() {
        assertDoesNotThrow(() -> administration.setHiddenByAdministrator(admin.getId(), user.getId(), true));
        assertThrows(BadRequestException.class, () -> administration.setHiddenByAdministrator(host.getId(), host.getId(), true));
    }

    private static User account(Long id, String roleName) {
        User user = new User();
        user.setId(id);
        user.setUsername("account" + id);
        user.setActive(true);
        user.setRole(new Role(roleName));
        return user;
    }
}
