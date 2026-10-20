CREATE TABLE IF NOT EXISTS public.notification_outbox (
    id varchar(36) PRIMARY KEY,
    receiver_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    notification_type varchar(30) NOT NULL,
    reference_id varchar(100),
    content varchar(255) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notification_outbox_due
    ON public.notification_outbox (next_attempt_at, created_at);

ALTER TABLE public.notifications
    ADD COLUMN IF NOT EXISTS outbox_event_id varchar(36);

ALTER TABLE public.notifications
    ALTER COLUMN reference_id TYPE varchar(100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_notifications_outbox_event
    ON public.notifications (outbox_event_id)
    WHERE outbox_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_receiver_id_desc
    ON public.notifications (receiver_id, id DESC);
