-- Restores the call schema from the original V20261103 (durable call sessions) and V20261104
-- (call history idempotency). Those versions were later reused for the document-date migrations,
-- so a fresh database never created call_sessions and V20261105 failed on it.
--
-- Databases that already ran the original scripts have every object below; on them this
-- migration is a no-op. Ordering it before V20261105 requires spring.flyway.out-of-order=true
-- on databases that are already past 20261105.
CREATE TABLE IF NOT EXISTS call_sessions (
    call_id varchar(100) PRIMARY KEY,
    caller_id bigint NOT NULL,
    callee_id bigint NOT NULL,
    call_type varchar(10) NOT NULL CHECK (call_type IN ('audio','video')),
    state varchar(12) NOT NULL CHECK (state IN ('RINGING','ACTIVE','ENDED','DECLINED','MISSED','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline_at timestamptz NOT NULL,
    answered_at timestamptz,
    ended_at timestamptz,
    processing_status varchar(12) NOT NULL DEFAULT 'COMPLETED'
        CHECK (processing_status IN ('PENDING','PROCESSING','RETRY','COMPLETED','FAILED')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token varchar(36),
    lease_until timestamptz,
    last_error varchar(100),
    CHECK (caller_id<>callee_id AND caller_id>0 AND callee_id>0)
);
CREATE INDEX IF NOT EXISTS ix_calls_ringing_deadline ON call_sessions(deadline_at,call_id) WHERE state='RINGING';
CREATE INDEX IF NOT EXISTS ix_calls_recovery ON call_sessions(processing_status,next_attempt_at,lease_until,created_at)
    WHERE state='MISSED' AND processing_status<>'COMPLETED';

-- No historical message is deleted, rewritten or silently selected as the winner.
-- Abort safely if old duplicates exist. See docs/quality/backend-bugfix-report.md preflight/repair procedure.
DO $$
BEGIN
    IF to_regclass('ux_doc_chat_call_id') IS NULL AND EXISTS (
        SELECT 1 FROM app_document_records
        WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
            AND NULLIF(payload->>'callId','') IS NOT NULL
        GROUP BY payload->>'callId' HAVING count(*)>1
    ) THEN
        RAISE EXCEPTION 'Duplicate historical CALL callId values require reviewed, non-destructive repair before V20261104.1';
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS ux_doc_chat_call_id ON app_document_records ((payload->>'callId'))
    WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
        AND NULLIF(payload->>'callId','') IS NOT NULL;
