-- Expand the existing recovery journal to all terminal calls. Old code continues to handle MISSED.
-- No historical state is rewritten or replayed; only newly finished calls are queued by the app.
CREATE INDEX IF NOT EXISTS ix_calls_terminal_recovery
    ON call_sessions(processing_status,next_attempt_at,lease_until,created_at,call_id)
    WHERE state IN ('MISSED','ENDED','DECLINED','CANCELLED') AND processing_status<>'COMPLETED';
