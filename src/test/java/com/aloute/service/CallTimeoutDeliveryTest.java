package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class CallTimeoutDeliveryTest {
    @Test
    void websocketFailureCannotPreventMissedHistoryOrDurableNotificationIntent() {
        var scheduler = mock(ScheduledExecutorService.class);
        var ws = mock(SimpMessagingTemplate.class);
        var users = mock(IUserRepository.class);
        User caller = user(1L, "caller"); User callee = user(2L, "callee");
        when(users.findById(1L)).thenReturn(Optional.of(caller));
        when(users.findById(2L)).thenReturn(Optional.of(callee));
        when(users.findAllById(any())).thenReturn(List.of(caller, callee));
        var notifications = mock(NotificationService.class);
        var history = mock(ChatService.class);
        var service = new CallTimeoutService(scheduler, ws, users, notifications, history);
        doThrow(new IllegalStateException("test broker unavailable"))
                .when(ws).convertAndSendToUser(eq("callee"), eq("/queue/call"), any());
        assertTrue(service.registerOffer("call-1", 1L, 2L, "audio"));
        var timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(timeout.capture(), anyLong(), eq(TimeUnit.SECONDS));

        assertDoesNotThrow(() -> timeout.getAllValues().getFirst().run());
        verify(history).createMissedCallMessage(1L, 2L, "call-1", "audio");
        verify(notifications).notify(eq(2L), eq(1L), eq("CALL_MISSED"), eq("call-1"), anyString());
        verify(ws).convertAndSendToUser(eq("caller"), eq("/queue/call"), any());
    }

    private User user(Long id, String username) {
        User user = new User(); user.setId(id); user.setUsername(username); return user;
    }
}
