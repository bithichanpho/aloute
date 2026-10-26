-- Versioned equivalent of the previous startup relationship migration.
-- Existing orphan/duplicate rows are preserved; invalid legacy constraints remain unvalidated
-- or are skipped, while future writes are still constrained where a NOT VALID FK exists.
DO $$
DECLARE
    r record;
    orphan_count bigint;
    duplicate_count bigint;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('friends', 'fk_friends_follower', 'FOREIGN KEY (follower_id) REFERENCES public.users(id)', 'SELECT count(*) FROM public.friends f LEFT JOIN public.users u ON u.id=f.follower_id WHERE u.id IS NULL'),
            ('friends', 'fk_friends_followed', 'FOREIGN KEY (followed_id) REFERENCES public.users(id)', 'SELECT count(*) FROM public.friends f LEFT JOIN public.users u ON u.id=f.followed_id WHERE u.id IS NULL'),
            ('close_friends', 'fk_close_friends_requester', 'FOREIGN KEY (requester_id) REFERENCES public.users(id)', 'SELECT count(*) FROM public.close_friends f LEFT JOIN public.users u ON u.id=f.requester_id WHERE u.id IS NULL'),
            ('close_friends', 'fk_close_friends_receiver', 'FOREIGN KEY (receiver_id) REFERENCES public.users(id)', 'SELECT count(*) FROM public.close_friends f LEFT JOIN public.users u ON u.id=f.receiver_id WHERE u.id IS NULL'),
            ('notifications', 'fk_notifications_receiver', 'FOREIGN KEY (receiver_id) REFERENCES public.users(id)', 'SELECT count(*) FROM public.notifications n LEFT JOIN public.users u ON u.id=n.receiver_id WHERE u.id IS NULL'),
            ('notifications', 'fk_notifications_sender', 'FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE SET NULL', 'SELECT count(*) FROM public.notifications n LEFT JOIN public.users u ON u.id=n.sender_id WHERE n.sender_id IS NOT NULL AND u.id IS NULL'),
            ('chat_groups', 'fk_chat_groups_creator', 'FOREIGN KEY (created_by) REFERENCES public.users(id)', 'SELECT count(*) FROM public.chat_groups g LEFT JOIN public.users u ON u.id=g.created_by WHERE u.id IS NULL'),
            ('group_members', 'fk_group_members_group', 'FOREIGN KEY (group_id) REFERENCES public.chat_groups(id) ON DELETE CASCADE', 'SELECT count(*) FROM public.group_members gm LEFT JOIN public.chat_groups g ON g.id=gm.group_id WHERE g.id IS NULL'),
            ('group_members', 'fk_group_members_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE', 'SELECT count(*) FROM public.group_members gm LEFT JOIN public.users u ON u.id=gm.user_id WHERE u.id IS NULL'),
            ('group_read_status', 'fk_group_read_status_group', 'FOREIGN KEY (group_id) REFERENCES public.chat_groups(id) ON DELETE CASCADE', 'SELECT count(*) FROM public.group_read_status r LEFT JOIN public.chat_groups g ON g.id=r.group_id WHERE g.id IS NULL'),
            ('group_read_status', 'fk_group_read_status_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE', 'SELECT count(*) FROM public.group_read_status r LEFT JOIN public.users u ON u.id=r.user_id WHERE u.id IS NULL'),
            ('communities', 'fk_communities_creator', 'FOREIGN KEY (created_by) REFERENCES public.users(id)', 'SELECT count(*) FROM public.communities c LEFT JOIN public.users u ON u.id=c.created_by WHERE u.id IS NULL'),
            ('community_members', 'fk_community_members_community', 'FOREIGN KEY (community_id) REFERENCES public.communities(id) ON DELETE CASCADE', 'SELECT count(*) FROM public.community_members cm LEFT JOIN public.communities c ON c.id=cm.community_id WHERE c.id IS NULL'),
            ('community_members', 'fk_community_members_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE', 'SELECT count(*) FROM public.community_members cm LEFT JOIN public.users u ON u.id=cm.user_id WHERE u.id IS NULL')
        ) AS fk(table_name, constraint_name, definition, orphan_sql)
    LOOP
        IF to_regclass('public.' || quote_ident(r.table_name)) IS NOT NULL
           AND to_regclass('public.users') IS NOT NULL THEN
            IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                           JOIN pg_namespace n ON n.oid=t.relnamespace
                           WHERE n.nspname='public' AND t.relname=r.table_name AND c.conname=r.constraint_name) THEN
                EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I %s NOT VALID', r.table_name, r.constraint_name, r.definition);
            END IF;
            EXECUTE r.orphan_sql INTO orphan_count;
            IF orphan_count = 0 THEN
                EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I', r.table_name, r.constraint_name);
            END IF;
        END IF;
    END LOOP;

    FOR r IN
        SELECT * FROM (VALUES
            ('friends', 'uk_friends_follower_followed', '(follower_id, followed_id)', 'SELECT count(*) FROM (SELECT follower_id, followed_id FROM public.friends GROUP BY 1,2 HAVING count(*)>1) d'),
            ('close_friends', 'uk_close_friends_requester_receiver', '(requester_id, receiver_id)', 'SELECT count(*) FROM (SELECT requester_id, receiver_id FROM public.close_friends GROUP BY 1,2 HAVING count(*)>1) d'),
            ('group_members', 'uk_group_members_group_user', '(group_id, user_id)', 'SELECT count(*) FROM (SELECT group_id, user_id FROM public.group_members GROUP BY 1,2 HAVING count(*)>1) d'),
            ('group_read_status', 'uk_group_read_status_group_user', '(group_id, user_id)', 'SELECT count(*) FROM (SELECT group_id, user_id FROM public.group_read_status GROUP BY 1,2 HAVING count(*)>1) d'),
            ('community_members', 'uk_community_members_community_user', '(community_id, user_id)', 'SELECT count(*) FROM (SELECT community_id, user_id FROM public.community_members GROUP BY 1,2 HAVING count(*)>1) d')
        ) AS uq(table_name, constraint_name, column_list, duplicate_sql)
    LOOP
        IF to_regclass('public.' || quote_ident(r.table_name)) IS NOT NULL THEN
            IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                           JOIN pg_namespace n ON n.oid=t.relnamespace
                           WHERE n.nspname='public' AND t.relname=r.table_name AND c.conname=r.constraint_name) THEN
                EXECUTE r.duplicate_sql INTO duplicate_count;
                IF duplicate_count = 0 THEN
                    EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I UNIQUE %s', r.table_name, r.constraint_name, r.column_list);
                END IF;
            END IF;
        END IF;
    END LOOP;

    FOR r IN
        SELECT * FROM (VALUES
            ('friends', 'idx_friends_follower', 'follower_id'),
            ('friends', 'idx_friends_followed', 'followed_id'),
            ('close_friends', 'idx_close_friends_requester_status', 'requester_id, status'),
            ('close_friends', 'idx_close_friends_receiver_status', 'receiver_id, status'),
            ('notifications', 'idx_notifications_receiver_created', 'receiver_id, created_at DESC'),
            ('notifications', 'idx_notifications_sender', 'sender_id'),
            ('chat_groups', 'idx_chat_groups_created_by', 'created_by'),
            ('group_members', 'idx_group_members_group', 'group_id'),
            ('group_members', 'idx_group_members_user', 'user_id'),
            ('group_read_status', 'idx_group_read_status_group', 'group_id'),
            ('group_read_status', 'idx_group_read_status_user', 'user_id'),
            ('communities', 'idx_communities_created_by', 'created_by'),
            ('community_members', 'idx_community_members_community', 'community_id'),
            ('community_members', 'idx_community_members_user', 'user_id'),
            ('communities', 'idx_communities_privacy_member_count', 'privacy, member_count DESC')
        ) AS ix(table_name, index_name, columns_sql)
    LOOP
        IF to_regclass('public.' || quote_ident(r.table_name)) IS NOT NULL THEN
            EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (%s)', r.index_name, r.table_name, r.columns_sql);
        END IF;
    END LOOP;
END $$;
