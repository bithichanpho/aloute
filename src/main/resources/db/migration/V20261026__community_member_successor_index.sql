-- Supports constant-memory community leave/admin-successor checks.
CREATE INDEX IF NOT EXISTS idx_community_members_community_role_joined
    ON public.community_members (community_id, role, joined_at);
