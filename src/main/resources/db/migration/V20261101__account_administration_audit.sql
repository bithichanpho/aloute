CREATE TABLE IF NOT EXISTS public.account_admin_audit (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    action varchar(30) NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_account_admin_audit_user_created
    ON public.account_admin_audit (user_id, created_at DESC, id DESC);
