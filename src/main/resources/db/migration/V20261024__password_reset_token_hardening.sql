-- 1. Scrub legacy plaintext reset tokens.
--    Current code stores only the SHA-256 hex digest (64 lowercase hex chars). Anything else is a
--    raw UUID written by an older version. Reset links expire after 30 minutes, so these rows are
--    already unusable; clearing them just removes secrets stored in plaintext.
UPDATE public.users
   SET reset_password_token = NULL,
       reset_password_expiry = NULL
 WHERE reset_password_token IS NOT NULL
   AND reset_password_token !~ '^[0-9a-f]{64}$';

-- 2. Reset-token lookup (findByResetPasswordToken) had no index and scanned users.
--    Partial index: only the few rows with an outstanding reset request are indexed.
CREATE INDEX IF NOT EXISTS idx_users_reset_password_token
    ON public.users (reset_password_token)
    WHERE reset_password_token IS NOT NULL;
