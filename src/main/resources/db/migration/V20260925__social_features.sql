-- Versioned PostgreSQL migration for account-deactivation/session-revocation fields.
-- The guards allow first-time dev bootstrap where Hibernate creates the baseline tables afterward.
DO $$
BEGIN
    IF to_regclass('public.users') IS NOT NULL THEN
        ALTER TABLE public.users ADD COLUMN IF NOT EXISTS self_deactivated boolean;
        ALTER TABLE public.users ADD COLUMN IF NOT EXISTS tokens_revoked_at timestamp with time zone;
    END IF;
END $$;
