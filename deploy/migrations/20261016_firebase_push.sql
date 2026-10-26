-- Firebase Installation IDs (FID) for browser/device push notifications.
CREATE TABLE IF NOT EXISTS user_push_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    fid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_push_tokens_fid UNIQUE (fid)
);

CREATE INDEX IF NOT EXISTS idx_user_push_tokens_user_id
    ON user_push_tokens (user_id);
