package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.ContentReport;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.repository.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SocialFeatureAccountModerationTest {
    @Test
    void legacyReportModerationKeepsAccountStateRevocationAndReportAuditBehavior() {
        var users = mock(IUserRepository.class);
        User actor = user(9L, "admin", "ROLE_ADMIN");
        User target = user(7L, "alice", "ROLE_USER");
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(users.findById(7L)).thenReturn(Optional.of(target));
        var store = mock(PostgresDocumentStore.class);
        var tokens = mock(RefreshTokenService.class);
        var cache = mock(AuthenticationUserCache.class);
        var service = new SocialFeatureService(store, mock(ContentAccessService.class), users,
                mock(IFriendRepository.class), mock(ICloseFriendRepository.class), mock(PostService.class),
                mock(ModerationService.class), mock(AuthRateLimitService.class), mock(NotificationService.class),
                mock(PostModerationCoordinator.class), tokens, cache, mock(MediaAssetLifecycleService.class));

        service.moderate(9L, "USER", "7", "HIDE", null);

        assertFalse(target.isActive());
        verify(users).save(target);
        verify(tokens).revokeAll(7L, target.getTokensRevokedAt());
        verify(cache).invalidate("alice");
        verify(store).save(argThat((ContentReport report) -> "RESOLVED".equals(report.getStatus())
                && "HIDE".equals(report.getDecision()) && Long.valueOf(9L).equals(report.getReviewedBy())
                && "USER".equals(report.getTargetType()) && "7".equals(report.getTargetId())));
    }

    private User user(Long id, String name, String role) {
        User user = new User(); user.setId(id); user.setUsername(name);
        user.setRole(new Role(role)); user.setActive(true); return user;
    }
}
