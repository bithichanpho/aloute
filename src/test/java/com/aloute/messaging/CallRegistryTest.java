package com.aloute.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.messaging.api.MissedCallHistory;
import com.aloute.messaging.calls.application.CallRegistryService;
import com.aloute.notifications.api.Notifications;
import com.aloute.users.api.CallParticipants;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class CallRegistryTest {
    @Test
    void timeoutIsIdempotentAndLateAnswerCannotReviveTheCall() {
        Fixture f = new Fixture();
        assertTrue(f.calls.registerOffer("c", 1L, 2L, "audio"));
        Runnable timeout = f.timeout();
        timeout.run(); timeout.run();
        assertFalse(f.calls.confirmAnswer("c", 2L, 1L));
        assertTrue(f.calls.finishCall("c", 1L, 2L).isEmpty());
        verify(f.history).createMissedCallMessage(1L, 2L, "c", "audio");
        verify(f.notifications).notify(eq(2L), eq(1L), eq("CALL_MISSED"), eq("c"), anyString());
    }

    @Test
    void answerWinningTimeoutRacePreventsMissedCallAndDoubleHangupIsHarmless() {
        Fixture f = new Fixture();
        f.calls.registerOffer("c", 1L, 2L, "audio");
        Runnable timeout = f.timeout();
        assertTrue(f.calls.confirmAnswer("c", 2L, 1L));
        timeout.run();
        assertTrue(f.calls.authorizePeerSignal("c", 1L, 2L));
        assertFalse(f.calls.authorizePeerSignal("c", 3L, 2L));
        assertTrue(f.calls.finishCall("c", 1L, 2L).isPresent());
        assertTrue(f.calls.finishCall("c", 2L, 1L).isEmpty());
        verifyNoInteractions(f.history, f.notifications);
    }

    @Test
    void concurrentAnswerAndTimeoutHaveOneConsistentLocalWinner() throws Exception {
        Fixture f = new Fixture();
        f.calls.registerOffer("c", 1L, 2L, "audio");
        Runnable timeout = f.timeout();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> answer = executor.submit(() -> {
                start.await(); return f.calls.confirmAnswer("c", 2L, 1L);
            });
            Future<?> expiry = executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                timeout.run();
            });
            start.countDown();
            boolean answered = answer.get(5, TimeUnit.SECONDS);
            expiry.get(5, TimeUnit.SECONDS);
            assertEquals(answered, f.calls.findCall("c").isPresent());
            verify(f.history, times(answered ? 0 : 1)).createMissedCallMessage(1L, 2L, "c", "audio");
        }
    }

    private static class Fixture {
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final MissedCallHistory history = mock(MissedCallHistory.class);
        final Notifications notifications = mock(Notifications.class);
        final CallRegistryService calls;

        Fixture() {
            CallParticipants users = mock(CallParticipants.class);
            when(users.lookup(any())).thenReturn(Map.of(
                    1L, new CallParticipants.Participant(1L, "caller", null, null),
                    2L, new CallParticipants.Participant(2L, "callee", null, null)));
            calls = new CallRegistryService(scheduler, mock(SimpMessagingTemplate.class), users,
                    notifications, history, null, false);
        }

        Runnable timeout() {
            var tasks = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler, times(2)).schedule(tasks.capture(), anyLong(), eq(TimeUnit.SECONDS));
            return tasks.getAllValues().getFirst();
        }
    }
}
