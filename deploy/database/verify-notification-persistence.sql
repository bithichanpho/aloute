-- Run in the same PostgreSQL connection/database used by the running ALOUTE process.
-- These results should still exist after stopping and starting the Spring Boot application.
SELECT current_database() AS database_name, current_schema() AS schema_name;

SELECT id, receiver_id, sender_id, type, reference_id, content, is_read, created_at
FROM public.notifications
ORDER BY id DESC
LIMIT 50;

-- Pending rows mean the DB-backed worker is waiting or retrying an external delivery channel.
SELECT id, receiver_id, sender_id, notification_type, status, attempt_count,
       next_attempt_at, in_app_delivered_at, last_error, created_at
FROM public.notification_outbox
ORDER BY created_at DESC
LIMIT 50;
