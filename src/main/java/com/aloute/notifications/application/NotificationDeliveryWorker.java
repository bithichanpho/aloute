package com.aloute.notifications.application;

import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.repository.INotificationOutboxRepository;
import java.time.LocalDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

/** Retries database, WebSocket and FCM notification delivery from a durable outbox. */
public class NotificationDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    private static final int DELIVERY_BATCH_SIZE = 10;
    public static final int MAX_DELIVERY_ATTEMPTS = 20;
    private final INotificationOutboxRepository repository;
    private final NotificationApplicationService notificationService;

    public NotificationDeliveryWorker(
            INotificationOutboxRepository repository, NotificationApplicationService notificationService) {
        this.repository = repository;
        this.notificationService = notificationService;
    }

    @Scheduled(
            fixedDelayString = "${app.notification-outbox.fixed-delay-ms:2000}",
            initialDelayString = "${app.notification-outbox.initial-delay-ms:5000}")
    // A reliable FCM attempt may take up to ten seconds. Keep the batch bounded and the
    // distributed lock longer than the worst-case batch so a second replica cannot redeliver the
    // same events while the first replica is still working through them.
    @SchedulerLock(name = "notificationOutboxWorker", lockAtMostFor = "PT3M")
    public void deliverDueEvents() {
        var events = repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                NotificationOutboxEvent.STATUS_PENDING,
                LocalDateTime.now(),
                PageRequest.of(0, DELIVERY_BATCH_SIZE));
        if (events.isEmpty()) return;
        java.util.Map<Long, String> directory = java.util.Map.of();
        java.util.Set<String> pendingRequests = java.util.Set.of();
        RuntimeException directoryFailure = null;
        try {
            directory = notificationService.deliveryDirectory(events);
            pendingRequests = notificationService.pendingDeliveryRequests(events);
        } catch (RuntimeException failure) {
            directoryFailure = failure;
        }
        for (NotificationOutboxEvent event : events) {
            try {
                if (directoryFailure != null) throw directoryFailure;
                if ("FOLLOW_REQUEST".equals(event.getNotificationType()))
                    notificationService.deliverQueued(event, directory, pendingRequests);
                else notificationService.deliverQueued(event, directory);
                repository.deleteById(event.getId());
            } catch (RuntimeException failure) {
                int attempts = event.getAttemptCount() + 1;
                LocalDateTime failedAt = LocalDateTime.now();
                event.setLastError(summarize(failure));
                long seconds = Math.min(3600L, 1L << Math.min(12, attempts));
                event.setAttemptCount(attempts);
                if (attempts >= MAX_DELIVERY_ATTEMPTS) {
                    event.setStatus(NotificationOutboxEvent.STATUS_DEAD);
                    event.setFailedAt(failedAt);
                    repository.save(event);
                    log.error(
                            "Notification outbox moved to dead letter (eventId={}, attempts={})",
                            event.getId(), attempts, failure);
                    continue;
                }
                event.setNextAttemptAt(failedAt.plusSeconds(seconds));
                repository.save(event);
                log.warn(
                        "Notification outbox delivery failed (eventId={}, attempt={}, cause={})",
                        event.getId(), attempts, failure.getClass().getSimpleName());
            }
        }
    }

    private String summarize(RuntimeException failure) {
        String message = failure.getMessage();
        String summary = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return summary.length() <= 1000 ? summary : summary.substring(0, 1000);
    }
}
