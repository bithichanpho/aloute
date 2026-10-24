package com.aloute.administration.application;

import com.aloute.administration.api.AccountModeration;
import com.aloute.identity.api.SessionInvalidation;
import com.aloute.users.api.UserAccountAdministration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One PostgreSQL transaction for the user-state change and the identity revocation. */
@Service
public class AccountModerationService implements AccountModeration {
    private final UserAccountAdministration accounts;
    private final SessionInvalidation sessions;

    public AccountModerationService(UserAccountAdministration accounts, SessionInvalidation sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @Transactional
    public void setHidden(Long actorId, Long targetId, boolean hidden) {
        var changed = accounts.setHiddenByAdministrator(actorId, targetId, hidden);
        sessions.revokeAll(changed.id(), changed.revokedAt(), changed.username());
    }
}
