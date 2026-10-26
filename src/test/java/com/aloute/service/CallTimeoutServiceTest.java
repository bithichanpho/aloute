package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.repository.IUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.ScheduledExecutorService;

class CallTimeoutServiceTest {
    private CallTimeoutService service() {
        return new CallTimeoutService(
                mock(ScheduledExecutorService.class),
                mock(SimpMessagingTemplate.class),
                mock(IUserRepository.class),
                mock(NotificationService.class),
                mock(ChatService.class));
    }

    @Test
    void onlyRegisteredCalleeCanAnswerAndOnlyParticipantsCanSendPeerSignals() {
        CallTimeoutService service = service();
        assertTrue(service.registerOffer("call-1", 10L, 20L, "video"));
        assertFalse(service.confirmAnswer("call-1", 10L, 20L)); // caller cannot answer itself
        assertFalse(service.confirmAnswer("call-1", 30L, 10L)); // third party cannot answer
        assertTrue(service.confirmAnswer("call-1", 20L, 10L));
        assertTrue(service.authorizePeerSignal("call-1", 10L, 20L));
        assertFalse(service.authorizePeerSignal("call-1", 30L, 10L));
    }

    @Test
    void guessedCallIdCannotCancelAnotherCallsTimeout() {
        CallTimeoutService service = service();
        assertTrue(service.registerOffer("call-owned-by-10", 10L, 20L, "audio"));
        assertTrue(service.finishCall("call-owned-by-10", 30L, 20L).isEmpty());
        assertTrue(service.findCall("call-owned-by-10").isPresent());
        assertTrue(service.finishCall("call-owned-by-10", 20L, 10L).isPresent());
        assertTrue(service.findCall("call-owned-by-10").isEmpty());
    }

    @Test
    void cannotRegisterCallIdForDifferentParticipants() {
        CallTimeoutService service = service();
        assertTrue(service.registerOffer("call-1", 10L, 20L, "video"));
        assertFalse(service.registerOffer("call-1", 10L, 30L, "video"));
    }
}
