-- Repair production databases where the earlier migration was not applied,
-- or where refresh_tokens was created outside the schema Hibernate validates.
CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_active
    ON public.refresh_tokens (user_id, revoked_at, expires_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_refresh_tokens_hash
    ON public.refresh_tokens (token_hash);
