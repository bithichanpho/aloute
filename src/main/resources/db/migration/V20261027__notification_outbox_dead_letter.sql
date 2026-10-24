ALTER TABLE public.notification_outbox
    ADD COLUMN IF NOT EXISTS status varchar(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE public.notification_outbox
    ADD COLUMN IF NOT EXISTS last_error varchar(1000);

ALTER TABLE public.notification_outbox
    ADD COLUMN IF NOT EXISTS failed_at timestamp;

CREATE INDEX IF NOT EXISTS idx_notification_outbox_status_due
    ON public.notification_outbox (status, next_attempt_at, created_at);
