-- Every group membership needs a read cursor. Without one, all historical messages are counted as
-- unread and the application must fall back to an insert during the read request.
INSERT INTO public.group_read_status (group_id, user_id, last_read_at)
SELECT member.group_id, member.user_id, member.joined_at
FROM public.group_members member
JOIN public.users app_user
  ON app_user.id = member.user_id
JOIN public.chat_groups chat_group
  ON chat_group.id = member.group_id
WHERE NOT EXISTS (
    SELECT 1
    FROM public.group_read_status status
    WHERE status.group_id = member.group_id
      AND status.user_id = member.user_id
);
