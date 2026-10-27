-- Track WebSocket delivery independently from Firebase Cloud Messaging retries.
-- Notification rows themselves remain durable in public.notifications.
ALTER TABLE public.notification_outbox
    ADD COLUMN IF NOT EXISTS in_app_delivered_at TIMESTAMP WITHOUT TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_notification_outbox_in_app_delivery
    ON public.notification_outbox (in_app_delivered_at, next_attempt_at, created_at);
