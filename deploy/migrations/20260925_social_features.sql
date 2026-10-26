ALTER TABLE users ADD COLUMN IF NOT EXISTS self_deactivated boolean;
ALTER TABLE users ADD COLUMN IF NOT EXISTS tokens_revoked_at timestamp with time zone;
