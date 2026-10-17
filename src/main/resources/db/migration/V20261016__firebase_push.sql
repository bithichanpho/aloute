-- Firebase Installation IDs (FID) for browser/device push notifications.
CREATE TABLE IF NOT EXISTS public.user_push_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    fid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_push_tokens_fid UNIQUE (fid)
);
CREATE INDEX IF NOT EXISTS idx_user_push_tokens_user_id
    ON public.user_push_tokens (user_id);

-- Also enforce FID uniqueness when the table was created by an earlier Hibernate update.
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_push_tokens_fid
    ON public.user_push_tokens (fid);
