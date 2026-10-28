-- Ensure legacy communities always have an owner so owner-only controls are reachable.
-- Existing OWNER/ADMIN assignments are preserved. For ownerless communities, promote the
-- earliest current MANAGER first, otherwise the earliest regular MEMBER.
WITH ownerless AS (
    SELECT c.id AS community_id
    FROM public.communities c
    WHERE NOT EXISTS (
        SELECT 1 FROM public.community_members cm
        WHERE cm.community_id = c.id AND cm.role IN ('OWNER', 'ADMIN')
    )
), ranked_candidates AS (
    SELECT cm.id AS member_id,
           ROW_NUMBER() OVER (
               PARTITION BY cm.community_id
               ORDER BY CASE cm.role WHEN 'MANAGER' THEN 0 ELSE 1 END,
                        cm.joined_at ASC, cm.id ASC
           ) AS rn
    FROM public.community_members cm
    JOIN ownerless o ON o.community_id = cm.community_id
    WHERE cm.role IN ('MANAGER', 'MEMBER')
)
UPDATE public.community_members cm
SET role = 'OWNER'
FROM ranked_candidates candidate
WHERE cm.id = candidate.member_id AND candidate.rn = 1;

-- If the creator has no membership row, restore ownership even if the group only has pending/banned rows.
INSERT INTO public.community_members (community_id, user_id, role, joined_at)
SELECT c.id, c.created_by, 'OWNER', COALESCE(c.created_at, CURRENT_TIMESTAMP)
FROM public.communities c
JOIN public.users u ON u.id = c.created_by
WHERE NOT EXISTS (
    SELECT 1 FROM public.community_members cm
    WHERE cm.community_id = c.id AND cm.user_id = c.created_by
)
ON CONFLICT (community_id, user_id) DO NOTHING;

-- Keep the cached member count consistent with active membership roles.
UPDATE public.communities c
SET member_count = (
    SELECT COUNT(*)::integer
    FROM public.community_members cm
    WHERE cm.community_id = c.id AND cm.role IN ('OWNER', 'ADMIN', 'MANAGER', 'MEMBER')
)
WHERE c.member_count IS DISTINCT FROM (
    SELECT COUNT(*)::integer
    FROM public.community_members cm
    WHERE cm.community_id = c.id AND cm.role IN ('OWNER', 'ADMIN', 'MANAGER', 'MEMBER')
);
