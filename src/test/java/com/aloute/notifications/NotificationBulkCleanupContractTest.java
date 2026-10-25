package com.aloute.notifications;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.projection.DefaultMethodInvokingMethodInterceptor;

/** Exercises Spring Data's actual default-method dispatcher, with a bulk-command test double. */
class NotificationBulkCleanupContractTest {
    @Test
    void oldReferenceDeleteMethodsKeepLongResultAndDispatchExactlyOneBulkCommand() {
        AtomicInteger commands = new AtomicInteger();
        var notifications = repository(INotificationRepository.class, commands);
        var outbox = repository(INotificationOutboxRepository.class, commands);
        assertEquals(3L, notifications.deleteByReferenceIdIn(List.of("p1", "p2")));
        assertEquals(3L, outbox.deleteByReferenceIdIn(List.of("p1", "p2")));
        assertEquals(2, commands.get());
    }

    @Test
    void typedCommunityDeletionKeepsLegacySignaturesAndTypeRestriction() {
        AtomicInteger commands = new AtomicInteger();
        var notifications = repository(INotificationRepository.class, commands);
        var outbox = repository(INotificationOutboxRepository.class, commands);
        var types = List.of("COMMUNITY_JOIN_OK", "COMMUNITY_JOIN_NO", "COMMUNITY_ROLE");
        assertEquals(3L, notifications.deleteByReferenceIdAndTypeIn("42", types));
        assertEquals(3L, outbox.deleteByReferenceIdAndNotificationTypeIn("42", types));
        assertEquals(2, commands.get());
    }

    private <T> T repository(Class<T> type, AtomicInteger commands) {
        var factory = new ProxyFactory();
        factory.setInterfaces(type);
        factory.addAdvice(new DefaultMethodInvokingMethodInterceptor());
        factory.addAdvice((MethodInterceptor) invocation -> {
            String name = invocation.getMethod().getName();
            assertTrue(name.startsWith("bulkDelete"), "Must never select/load entities before a reference delete");
            if (name.equals("bulkDeleteByReferenceAndTypes")) {
                assertEquals("42", invocation.getArguments()[0]);
                assertEquals(List.of("COMMUNITY_JOIN_OK", "COMMUNITY_JOIN_NO", "COMMUNITY_ROLE"),
                        invocation.getArguments()[1]);
            }
            commands.incrementAndGet();
            return 3;
        });
        return type.cast(factory.getProxy());
    }
}
