package com.aloute.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.messaging.api.CallSignaling;
import com.aloute.messaging.api.MissedCallHistory;
import com.aloute.messaging.calls.application.CallRegistryService;
import com.aloute.notifications.api.Notifications;
import com.aloute.repository.IUserRepository;
import com.aloute.service.CallTimeoutService;
import com.aloute.users.infrastructure.JpaCallParticipants;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class CallRegistryWiringTest {
    @Test
    void productionPortAndCompatibilityFacadeShareOneStateMachine() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("callTimeoutExecutor", ScheduledExecutorService.class,
                    () -> mock(ScheduledExecutorService.class));
            context.registerBean(SimpMessagingTemplate.class, () -> mock(SimpMessagingTemplate.class));
            context.registerBean(IUserRepository.class, () -> mock(IUserRepository.class));
            context.registerBean(Notifications.class, () -> mock(Notifications.class));
            context.registerBean(MissedCallHistory.class, () -> mock(MissedCallHistory.class));
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            var sessions = mock(com.aloute.messaging.calls.domain.CallSessions.class);
            var ringing = new com.aloute.messaging.calls.domain.CallSessions.Snapshot("c", 1L, 2L, "video", false,
                    java.time.Instant.now(), "RINGING");
            var active = new com.aloute.messaging.calls.domain.CallSessions.Snapshot("c", 1L, 2L, "video", true,
                    ringing.createdAt(), "ACTIVE");
            when(sessions.register("c", 1L, 2L, "video")).thenReturn(true);
            when(sessions.active("c")).thenReturn(java.util.Optional.of(ringing), java.util.Optional.of(active), java.util.Optional.empty());
            when(sessions.answer("c", 2L, 1L)).thenReturn(true);
            when(sessions.finish("c", 1L, 2L)).thenReturn(java.util.Optional.of(active));
            context.registerBean(com.aloute.messaging.calls.domain.CallSessions.class, () -> sessions);
            context.registerBean(com.aloute.messaging.calls.application.CallCompletionService.class,
                    () -> mock(com.aloute.messaging.calls.application.CallCompletionService.class));
            context.register(JpaCallParticipants.class, CallRegistryService.class, CallTimeoutService.class);
            context.refresh();
            CallSignaling api = context.getBean(CallSignaling.class);
            assertInstanceOf(CallRegistryService.class, api);
            CallTimeoutService facade = context.getBean(CallTimeoutService.class);
            assertTrue(facade.registerOffer("c", 1L, 2L, "video"));
            assertTrue(api.findCall("c").isPresent());
            assertTrue(api.confirmAnswer("c", 2L, 1L));
            assertTrue(facade.findCall("c").orElseThrow().answered());
            assertTrue(facade.finishCall("c", 1L, 2L).isPresent());
            assertTrue(api.findCall("c").isEmpty());
        }
    }
}
