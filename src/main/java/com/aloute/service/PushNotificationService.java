package com.aloute.service;

import com.aloute.model.entity.UserPushToken;
import com.aloute.repository.IUserPushTokenRepository;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushNotificationService implements com.aloute.notifications.api.ReliableNotificationPush {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private final IUserPushTokenRepository tokenRepository;
    private final FirebaseAdminService firebaseAdminService;

    public PushNotificationService(
            IUserPushTokenRepository tokenRepository, FirebaseAdminService firebaseAdminService) {
        this.tokenRepository = tokenRepository;
        this.firebaseAdminService = firebaseAdminService;
    }

    @Transactional
    public void register(Long userId, String rawFid) {
        String fid = rawFid == null ? "" : rawFid.trim();
        if (fid.isBlank() || fid.length() > 255) {
            throw new IllegalArgumentException("Firebase Installation ID không hợp lệ");
        }
        UserPushToken entry = tokenRepository.findByFid(fid).orElseGet(UserPushToken::new);
        // One browser installation can belong to one ALOUTE account at a time.
        entry.setUserId(userId);
        entry.setFid(fid);
        tokenRepository.save(entry);
    }

    @Transactional
    public void unregister(Long userId, String rawFid) {
        if (rawFid == null || rawFid.isBlank()) return;
        tokenRepository.deleteByUserIdAndFid(userId, rawFid.trim());
    }

    public void sendToUser(Long userId, String title, String body, String relativeUrl) {
        sendToUser(userId, title, body, relativeUrl, "NOTIFICATION");
    }

    public void sendChatMessageToUser(Long userId, String title, String body, String relativeUrl) {
        sendToUser(userId, title, body, relativeUrl, "CHAT_MESSAGE");
    }

    /**
     * Outbox delivery waits for Firebase acknowledgement. Transient failures are propagated so the
     * durable worker retries; permanently unregistered installations are removed and skipped.
     */
    public void sendNotificationToUserReliably(
            Long userId, String title, String body, String relativeUrl) {
        if (userId == null || !firebaseAdminService.isMessagingEnabled()) return;
        for (UserPushToken subscription : tokenRepository.findAllByUserId(userId)) {
            String fid = subscription.getFid();
            try {
                firebaseAdminService
                        .sendDataMessage(fid, title, body, relativeUrl, "NOTIFICATION")
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                if (cause instanceof FirebaseMessagingException firebaseError
                        && firebaseError.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                    tokenRepository.deleteByFid(fid);
                    continue;
                }
                throw new IllegalStateException("Firebase notification delivery failed", cause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Firebase notification delivery interrupted", interrupted);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new IllegalStateException("Firebase notification delivery timed out", timeout);
            } catch (RuntimeException dispatchFailure) {
                throw new IllegalStateException("Firebase notification dispatch failed", dispatchFailure);
            }
        }
    }

    private void sendToUser(Long userId, String title, String body, String relativeUrl, String type) {
        if (userId == null || !firebaseAdminService.isMessagingEnabled()) return;
        for (UserPushToken subscription : tokenRepository.findAllByUserId(userId)) {
            String fid = subscription.getFid();
            try {
                ApiFuture<String> future =
                        firebaseAdminService.sendDataMessage(fid, title, body, relativeUrl, type);
                ApiFutures.addCallback(
                        future,
                        new ApiFutureCallback<>() {
                            @Override
                            public void onSuccess(String messageId) {
                                log.debug("FCM message accepted for user {}", userId);
                            }

                            @Override
                            public void onFailure(Throwable error) {
                                if (error instanceof FirebaseMessagingException firebaseError
                                        && firebaseError.getMessagingErrorCode()
                                                == MessagingErrorCode.UNREGISTERED) {
                                    tokenRepository.deleteByFid(fid);
                                } else {
                                    log.debug(
                                            "FCM push failed (user={}, cause={})",
                                            userId,
                                            error.getClass().getSimpleName());
                                }
                            }
                        },
                        MoreExecutors.directExecutor());
            } catch (Exception ex) {
                log.debug(
                        "Could not dispatch FCM push (user={}, cause={})",
                        userId,
                        ex.getClass().getSimpleName());
            }
        }
    }
}
