package com.aloute.administration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.administration.api.AccountModeration;
import com.aloute.administration.application.AccountModerationService;
import com.aloute.identity.api.SessionInvalidation;
import com.aloute.users.api.UserAccountAdministration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class AccountModerationServiceTest {
    @Test
    void orchestratesOwnedWritesAndCommitsThroughSpringTransactionInterceptor() {
        var users = mock(UserAccountAdministration.class);
        var sessions = mock(SessionInvalidation.class);
        var transactions = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        Instant time = Instant.EPOCH;
        when(users.setHiddenByAdministrator(9L, 7L, true))
                .thenReturn(new UserAccountAdministration.ChangedAccount(7L, "alice", time));
        try (var context = context(users, sessions, transactions)) {
            context.getBean(AccountModeration.class).setHidden(9L, 7L, true);
            var order = inOrder(users, sessions, transactions);
            order.verify(users).setHiddenByAdministrator(9L, 7L, true);
            order.verify(sessions).revokeAll(7L, time, "alice");
            order.verify(transactions).commit(status);
        }
    }

    @Test
    void revocationFailureRollsBackTheOwningTransaction() {
        var users = mock(UserAccountAdministration.class);
        var sessions = mock(SessionInvalidation.class);
        var transactions = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        when(users.setHiddenByAdministrator(9L, 7L, true))
                .thenReturn(new UserAccountAdministration.ChangedAccount(7L, "alice", Instant.EPOCH));
        doThrow(new IllegalStateException("revocation unavailable")).when(sessions)
                .revokeAll(7L, Instant.EPOCH, "alice");
        try (var context = context(users, sessions, transactions)) {
            assertThrows(IllegalStateException.class,
                    () -> context.getBean(AccountModeration.class).setHidden(9L, 7L, true));
            verify(transactions).rollback(status);
            verify(transactions, never()).commit(any());
        }
    }

    private AnnotationConfigApplicationContext context(UserAccountAdministration users,
            SessionInvalidation sessions, PlatformTransactionManager transactions) {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class, AccountModerationService.class);
        context.registerBean(UserAccountAdministration.class, () -> users);
        context.registerBean(SessionInvalidation.class, () -> sessions);
        context.registerBean(PlatformTransactionManager.class, () -> transactions);
        context.refresh();
        return context;
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {}
}
