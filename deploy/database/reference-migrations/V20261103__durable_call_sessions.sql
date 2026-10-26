-- Expand only: call lifecycle and recovery journal, owned by Messaging/Calls.
-- Existing notification_outbox remains the sole notification delivery outbox.
CREATE TABLE call_sessions (
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
CREATE INDEX ix_calls_ringing_deadline ON call_sessions(deadline_at,call_id) WHERE state='RINGING';
CREATE INDEX ix_calls_recovery ON call_sessions(processing_status,next_attempt_at,lease_until,created_at)
    WHERE state='MISSED' AND processing_status<>'COMPLETED';
