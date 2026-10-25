package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.repository.INotificationOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class NotificationOutboxWorkerTest {
    @Test
    void deletesEventOnlyAfterSuccessfulDelivery() {
        INotificationOutboxRepository repository = org.mockito.Mockito.mock(INotificationOutboxRepository.class);
        NotificationService notifications = org.mockito.Mockito.mock(NotificationService.class);
        NotificationOutboxEvent event = event();
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(NotificationOutboxEvent.STATUS_PENDING), any(LocalDateTime.class),
                any(Pageable.class))).thenReturn(List.of(event));

        new NotificationOutboxWorker(repository, notifications).deliverDueEvents();

        verify(repository).findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(NotificationOutboxEvent.STATUS_PENDING), any(LocalDateTime.class),
                argThat(page -> page.getPageSize() == 10));
        verify(notifications).deliveryDirectory(List.of(event));
        verify(notifications).deliverQueued(eq(event), org.mockito.ArgumentMatchers.anyMap());
        verify(repository).deleteById(event.getId());
        verify(repository, never()).save(event);
    }

    @Test
    void retriesFailedDeliveryWithBackoff() {
        INotificationOutboxRepository repository = org.mockito.Mockito.mock(INotificationOutboxRepository.class);
        NotificationService notifications = org.mockito.Mockito.mock(NotificationService.class);
        NotificationOutboxEvent event = event();
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(NotificationOutboxEvent.STATUS_PENDING), any(LocalDateTime.class),
                any(Pageable.class))).thenReturn(List.of(event));
        doThrow(new IllegalStateException("temporary failure")).when(notifications).deliverQueued(eq(event), org.mockito.ArgumentMatchers.anyMap());
        LocalDateTime before = LocalDateTime.now();

        new NotificationOutboxWorker(repository, notifications).deliverDueEvents();

        assertEquals(1, event.getAttemptCount());
        org.junit.jupiter.api.Assertions.assertTrue(event.getNextAttemptAt().isAfter(before));
        verify(repository).save(event);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void movesPoisonEventToDeadLetterAfterMaximumAttempts() {
        INotificationOutboxRepository repository = org.mockito.Mockito.mock(INotificationOutboxRepository.class);
        NotificationService notifications = org.mockito.Mockito.mock(NotificationService.class);
        NotificationOutboxEvent event = event();
        event.setAttemptCount(NotificationOutboxWorker.MAX_DELIVERY_ATTEMPTS - 1);
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(NotificationOutboxEvent.STATUS_PENDING), any(LocalDateTime.class),
                any(Pageable.class))).thenReturn(List.of(event));
        doThrow(new IllegalStateException("poison payload")).when(notifications).deliverQueued(eq(event), org.mockito.ArgumentMatchers.anyMap());

        new NotificationOutboxWorker(repository, notifications).deliverDueEvents();

        assertEquals(NotificationOutboxWorker.MAX_DELIVERY_ATTEMPTS, event.getAttemptCount());
        assertEquals(NotificationOutboxEvent.STATUS_DEAD, event.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(event.getFailedAt());
        org.junit.jupiter.api.Assertions.assertTrue(event.getLastError().contains("poison payload"));
        verify(repository).save(event);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void directoryFailureRetainsEveryEventForRetryWithoutSending() {
        INotificationOutboxRepository repository = org.mockito.Mockito.mock(INotificationOutboxRepository.class);
        NotificationService notifications = org.mockito.Mockito.mock(NotificationService.class);
        NotificationOutboxEvent first = event();
        NotificationOutboxEvent second = event();
        var events = List.of(first, second);
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(NotificationOutboxEvent.STATUS_PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(events);
        when(notifications.deliveryDirectory(events)).thenThrow(new IllegalStateException("directory unavailable"));

        new NotificationOutboxWorker(repository, notifications).deliverDueEvents();

        assertEquals(1, first.getAttemptCount());
        assertEquals(1, second.getAttemptCount());
        verify(repository).save(first);
        verify(repository).save(second);
        verify(repository, never()).deleteById(any());
        verify(notifications, never()).deliverQueued(any(), org.mockito.ArgumentMatchers.anyMap());
    }

    private NotificationOutboxEvent event() {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setReceiverId(2L);
        event.setSenderId(1L);
        event.setNotificationType("LIKE");
        event.setContent("đã thích bài viết của bạn");
        return event;
    }
}
