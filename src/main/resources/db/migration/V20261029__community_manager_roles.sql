-- Community ownership is scoped to each community and is separate from the system ADMIN role.
UPDATE public.community_members
SET role = 'OWNER'
WHERE role = 'ADMIN';

CREATE INDEX IF NOT EXISTS idx_community_members_community_role_joined
    ON public.community_members (community_id, role, joined_at);
