package com.aloute.notifications.api;

/** Called only after identity has authorized account deletion. */
public interface NotificationAccountCleanup {
    void removeAccountReferences(Long userId);
}
