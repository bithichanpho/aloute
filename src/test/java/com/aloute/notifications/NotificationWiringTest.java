package com.aloute.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.notifications.api.*;
import com.aloute.notifications.application.NotificationCleanupService;
import com.aloute.posts.api.PostNotificationLookup;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.service.NotificationOutboxService;
import com.aloute.service.NotificationOutboxWorker;
import com.aloute.service.NotificationService;
import com.aloute.social.api.ProfileVisibility;
import com.aloute.users.api.UserDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class NotificationWiringTest {
    @Test
    void compatibilityBeansExposeOneApiAndOneInheritedScheduledWorker() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
                    "app.notification-outbox.initial-delay-ms", "3600000")));
            context.registerBean(INotificationRepository.class, () -> mock(INotificationRepository.class));
            context.registerBean(INotificationOutboxRepository.class, () -> mock(INotificationOutboxRepository.class));
            context.registerBean(UserDirectory.class, () -> mock(UserDirectory.class));
            context.registerBean(PostNotificationLookup.class, () -> mock(PostNotificationLookup.class));
            context.registerBean(ProfileVisibility.class, () -> mock(ProfileVisibility.class));
            context.registerBean(ReliableNotificationPush.class, () -> mock(ReliableNotificationPush.class));
            context.registerBean(SimpMessagingTemplate.class, () -> mock(SimpMessagingTemplate.class));
            context.register(NotificationService.class, NotificationOutboxService.class,
                    NotificationOutboxWorker.class, NotificationCleanupService.class);
            context.registerBean(ScheduledAnnotationBeanPostProcessor.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(Notifications.class).size());
            assertEquals(1, context.getBeansOfType(NotificationIntents.class).size());
            assertEquals(1, context.getBeansOfType(NotificationCleanup.class).size());
            assertEquals(1, context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            var method = NotificationOutboxWorker.class.getMethod("deliverDueEvents");
            assertNotNull(method.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class));
        } catch (NoSuchMethodException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    void outboxAndCleanupHaveRealSpringTransactionInterceptors() {
        try (var context = new AnnotationConfigApplicationContext()) {
            CountingTransactions transactions = new CountingTransactions();
            var notifications = mock(INotificationRepository.class);
            var outbox = mock(INotificationOutboxRepository.class);
            context.register(TransactionConfiguration.class);
            context.registerBean(PlatformTransactionManager.class, () -> transactions);
            context.registerBean(INotificationRepository.class, () -> notifications);
            context.registerBean(INotificationOutboxRepository.class, () -> outbox);
            context.register(NotificationOutboxService.class, NotificationCleanupService.class);
            context.refresh();

            context.getBean(NotificationIntents.class).enqueue(2L, 1L, "FOLLOW", null, "followed");
            assertEquals(1, transactions.commits);
            doThrow(new IllegalStateException("storage failed"))
                    .when(outbox).deleteByReferenceIdIn(java.util.List.of("p"));
            assertThrows(IllegalStateException.class,
                    () -> context.getBean(NotificationCleanup.class).removePostReferences(java.util.List.of("p")));
            assertEquals(1, transactions.rollbacks);
        }
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {}

    private static class CountingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        private final ThreadLocal<State> current = new ThreadLocal<>();
        protected Object doGetTransaction() {
            State existing = current.get();
            return existing == null ? new State() : existing;
        }
        protected boolean isExistingTransaction(Object transaction) { return ((State) transaction).active; }
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            State state = (State) transaction;
            state.active = true;
            current.set(state);
        }
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((State) status.getTransaction()).rollbackOnly = true;
        }
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        protected void doCleanupAfterCompletion(Object transaction) { current.remove(); }
        private static class State implements org.springframework.transaction.support.SmartTransactionObject {
            boolean active;
            boolean rollbackOnly;
            public boolean isRollbackOnly() { return rollbackOnly; }
        }
    }
}
