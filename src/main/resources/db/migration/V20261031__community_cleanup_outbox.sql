CREATE TABLE IF NOT EXISTS public.community_cleanup_jobs (
    community_id bigint PRIMARY KEY,
    avatar_url varchar(500),
    cover_url varchar(500),
    attempt_count integer NOT NULL DEFAULT 0,
    last_error varchar(1000),
    next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_community_cleanup_jobs_due
    ON public.community_cleanup_jobs (next_attempt_at, created_at);
