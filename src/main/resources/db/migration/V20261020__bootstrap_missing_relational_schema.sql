-- Bootstrap SQL relational schema for a brand-new PostgreSQL database.
-- This migration is intentionally idempotent: existing deployments keep their data and
-- objects, while tables missing because Hibernate previously created them at runtime are
-- created before Hibernate's production ddl-auto=validate check.
--
-- This is derived from the current JPA entity mappings, not a live pg_dump. See
-- docs/DATABASE_MIGRATIONS.md to export/verify the real deployed schema before adopting a
-- production database with historical schema drift.

CREATE TABLE IF NOT EXISTS public.roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS public.users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    cover_url VARCHAR(500),
    bio VARCHAR(300),
    is_private BOOLEAN,
    role_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    self_deactivated BOOLEAN DEFAULT FALSE,
    tokens_revoked_at TIMESTAMPTZ,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verification_token VARCHAR(100),
    verification_token_expiry TIMESTAMP WITHOUT TIME ZONE,
    verification_email_sent_at TIMESTAMP WITHOUT TIME ZONE,
    reset_password_token VARCHAR(100),
    reset_password_expiry TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS public.pending_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    verification_token VARCHAR(100) NOT NULL UNIQUE,
    verification_token_expiry TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    verification_email_sent_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.friends (
    id BIGSERIAL PRIMARY KEY,
    follower_id BIGINT NOT NULL,
    followed_id BIGINT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_friends_follower_followed UNIQUE (follower_id, followed_id)
);

CREATE TABLE IF NOT EXISTS public.close_friends (
    id BIGSERIAL PRIMARY KEY,
    requester_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uk_close_friends_requester_receiver UNIQUE (requester_id, receiver_id)
);

CREATE TABLE IF NOT EXISTS public.notifications (
    id BIGSERIAL PRIMARY KEY,
    receiver_id BIGINT NOT NULL,
    sender_id BIGINT,
    type VARCHAR(30) NOT NULL,
    reference_id VARCHAR(50),
    content VARCHAR(255) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.chat_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.group_members (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_group_members_group_user UNIQUE (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS public.group_read_status (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    last_read_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_group_read_status_group_user UNIQUE (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS public.communities (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    avatar_url VARCHAR(500),
    cover_url VARCHAR(500),
    privacy VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    created_by BIGINT NOT NULL,
    member_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.community_members (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_community_members_community_user UNIQUE (community_id, user_id)
);

-- These tables were introduced by earlier migrations. Keep this migration safe on both
-- fresh databases and databases where those migrations were already applied.
CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE TABLE IF NOT EXISTS public.user_push_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    fid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_push_tokens_fid UNIQUE (fid)
);

-- Keep the token uniqueness declared by the PendingUser JPA mapping. Legacy duplicate rows
-- are preserved; in that case we log the issue rather than deleting data during migration.
DO $$
DECLARE
    duplicate_count BIGINT;
BEGIN
    SELECT count(*) INTO duplicate_count
    FROM (SELECT verification_token FROM public.pending_users
          GROUP BY verification_token HAVING count(*) > 1) duplicates;
    IF duplicate_count = 0 THEN
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS uk_pending_users_verification_token '
            || 'ON public.pending_users (verification_token)';
    ELSE
        RAISE WARNING 'Skipping uk_pending_users_verification_token because legacy duplicate verification tokens exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_pending_users_expires_at ON public.pending_users (expires_at);
CREATE INDEX IF NOT EXISTS idx_friends_follower ON public.friends (follower_id);
CREATE INDEX IF NOT EXISTS idx_friends_followed ON public.friends (followed_id);
CREATE INDEX IF NOT EXISTS idx_close_friends_requester_status ON public.close_friends (requester_id, status);
CREATE INDEX IF NOT EXISTS idx_close_friends_receiver_status ON public.close_friends (receiver_id, status);
CREATE INDEX IF NOT EXISTS idx_notifications_receiver ON public.notifications (receiver_id);
CREATE INDEX IF NOT EXISTS idx_notifications_receiver_created ON public.notifications (receiver_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_sender ON public.notifications (sender_id);
CREATE INDEX IF NOT EXISTS idx_chat_groups_created_by ON public.chat_groups (created_by);
CREATE INDEX IF NOT EXISTS idx_group_members_group ON public.group_members (group_id);
CREATE INDEX IF NOT EXISTS idx_group_members_user ON public.group_members (user_id);
CREATE INDEX IF NOT EXISTS idx_group_read_status_group ON public.group_read_status (group_id);
CREATE INDEX IF NOT EXISTS idx_group_read_status_user ON public.group_read_status (user_id);
CREATE INDEX IF NOT EXISTS idx_communities_created_by ON public.communities (created_by);
CREATE INDEX IF NOT EXISTS idx_communities_privacy_member_count ON public.communities (privacy, member_count DESC);
CREATE INDEX IF NOT EXISTS idx_community_members_community ON public.community_members (community_id);
CREATE INDEX IF NOT EXISTS idx_community_members_user ON public.community_members (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_active ON public.refresh_tokens (user_id, revoked_at, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_refresh_tokens_hash ON public.refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_user_push_tokens_user_id ON public.user_push_tokens (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_push_tokens_fid ON public.user_push_tokens (fid);

-- Relationships are installed NOT VALID first to preserve legacy orphan rows. PostgreSQL
-- still enforces them for future writes; clean datasets are validated immediately below.
DO $$
DECLARE
    r record;
    orphan_count BIGINT;
    duplicate_count BIGINT;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('users', 'fk_users_role', 'FOREIGN KEY (role_id) REFERENCES public.roles(id)'),
        ('friends', 'fk_friends_follower', 'FOREIGN KEY (follower_id) REFERENCES public.users(id)'),
        ('friends', 'fk_friends_followed', 'FOREIGN KEY (followed_id) REFERENCES public.users(id)'),
        ('close_friends', 'fk_close_friends_requester', 'FOREIGN KEY (requester_id) REFERENCES public.users(id)'),
        ('close_friends', 'fk_close_friends_receiver', 'FOREIGN KEY (receiver_id) REFERENCES public.users(id)'),
        ('notifications', 'fk_notifications_receiver', 'FOREIGN KEY (receiver_id) REFERENCES public.users(id)'),
        ('notifications', 'fk_notifications_sender', 'FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE SET NULL'),
        ('chat_groups', 'fk_chat_groups_creator', 'FOREIGN KEY (created_by) REFERENCES public.users(id)'),
        ('group_members', 'fk_group_members_group', 'FOREIGN KEY (group_id) REFERENCES public.chat_groups(id) ON DELETE CASCADE'),
        ('group_members', 'fk_group_members_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE'),
        ('group_read_status', 'fk_group_read_status_group', 'FOREIGN KEY (group_id) REFERENCES public.chat_groups(id) ON DELETE CASCADE'),
        ('group_read_status', 'fk_group_read_status_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE'),
        ('communities', 'fk_communities_creator', 'FOREIGN KEY (created_by) REFERENCES public.users(id)'),
        ('community_members', 'fk_community_members_community', 'FOREIGN KEY (community_id) REFERENCES public.communities(id) ON DELETE CASCADE'),
        ('community_members', 'fk_community_members_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE'),
        ('refresh_tokens', 'fk_refresh_tokens_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE'),
        ('user_push_tokens', 'fk_user_push_tokens_user', 'FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE')
    ) AS x(table_name, constraint_name, definition)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
            JOIN pg_namespace n ON n.oid=t.relnamespace
            WHERE n.nspname='public' AND t.relname=r.table_name AND c.conname=r.constraint_name
        ) THEN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I %s NOT VALID',
                r.table_name, r.constraint_name, r.definition);
        END IF;
    END LOOP;

    FOR r IN SELECT * FROM (VALUES
        ('users', 'fk_users_role', 'SELECT count(*) FROM public.users u LEFT JOIN public.roles r ON r.id=u.role_id WHERE r.id IS NULL'),
        ('friends', 'fk_friends_follower', 'SELECT count(*) FROM public.friends f LEFT JOIN public.users u ON u.id=f.follower_id WHERE u.id IS NULL'),
        ('friends', 'fk_friends_followed', 'SELECT count(*) FROM public.friends f LEFT JOIN public.users u ON u.id=f.followed_id WHERE u.id IS NULL'),
        ('close_friends', 'fk_close_friends_requester', 'SELECT count(*) FROM public.close_friends f LEFT JOIN public.users u ON u.id=f.requester_id WHERE u.id IS NULL'),
        ('close_friends', 'fk_close_friends_receiver', 'SELECT count(*) FROM public.close_friends f LEFT JOIN public.users u ON u.id=f.receiver_id WHERE u.id IS NULL'),
        ('notifications', 'fk_notifications_receiver', 'SELECT count(*) FROM public.notifications n LEFT JOIN public.users u ON u.id=n.receiver_id WHERE u.id IS NULL'),
        ('notifications', 'fk_notifications_sender', 'SELECT count(*) FROM public.notifications n LEFT JOIN public.users u ON u.id=n.sender_id WHERE n.sender_id IS NOT NULL AND u.id IS NULL'),
        ('chat_groups', 'fk_chat_groups_creator', 'SELECT count(*) FROM public.chat_groups g LEFT JOIN public.users u ON u.id=g.created_by WHERE u.id IS NULL'),
        ('group_members', 'fk_group_members_group', 'SELECT count(*) FROM public.group_members gm LEFT JOIN public.chat_groups g ON g.id=gm.group_id WHERE g.id IS NULL'),
        ('group_members', 'fk_group_members_user', 'SELECT count(*) FROM public.group_members gm LEFT JOIN public.users u ON u.id=gm.user_id WHERE u.id IS NULL'),
        ('group_read_status', 'fk_group_read_status_group', 'SELECT count(*) FROM public.group_read_status s LEFT JOIN public.chat_groups g ON g.id=s.group_id WHERE g.id IS NULL'),
        ('group_read_status', 'fk_group_read_status_user', 'SELECT count(*) FROM public.group_read_status s LEFT JOIN public.users u ON u.id=s.user_id WHERE u.id IS NULL'),
        ('communities', 'fk_communities_creator', 'SELECT count(*) FROM public.communities c LEFT JOIN public.users u ON u.id=c.created_by WHERE u.id IS NULL'),
        ('community_members', 'fk_community_members_community', 'SELECT count(*) FROM public.community_members cm LEFT JOIN public.communities c ON c.id=cm.community_id WHERE c.id IS NULL'),
        ('community_members', 'fk_community_members_user', 'SELECT count(*) FROM public.community_members cm LEFT JOIN public.users u ON u.id=cm.user_id WHERE u.id IS NULL'),
        ('refresh_tokens', 'fk_refresh_tokens_user', 'SELECT count(*) FROM public.refresh_tokens t LEFT JOIN public.users u ON u.id=t.user_id WHERE u.id IS NULL'),
        ('user_push_tokens', 'fk_user_push_tokens_user', 'SELECT count(*) FROM public.user_push_tokens t LEFT JOIN public.users u ON u.id=t.user_id WHERE u.id IS NULL')
    ) AS x(table_name, constraint_name, orphan_sql)
    LOOP
        EXECUTE r.orphan_sql INTO orphan_count;
        IF orphan_count = 0 THEN
            EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I', r.table_name, r.constraint_name);
        END IF;
    END LOOP;

    FOR r IN SELECT * FROM (VALUES
        ('close_friends', 'uk_close_friends_requester_receiver', '(requester_id, receiver_id)', 'SELECT count(*) FROM (SELECT requester_id, receiver_id FROM public.close_friends GROUP BY 1,2 HAVING count(*) > 1) d'),
        ('group_members', 'uk_group_members_group_user', '(group_id, user_id)', 'SELECT count(*) FROM (SELECT group_id, user_id FROM public.group_members GROUP BY 1,2 HAVING count(*) > 1) d'),
        ('group_read_status', 'uk_group_read_status_group_user', '(group_id, user_id)', 'SELECT count(*) FROM (SELECT group_id, user_id FROM public.group_read_status GROUP BY 1,2 HAVING count(*) > 1) d'),
        ('community_members', 'uk_community_members_community_user', '(community_id, user_id)', 'SELECT count(*) FROM (SELECT community_id, user_id FROM public.community_members GROUP BY 1,2 HAVING count(*) > 1) d')
    ) AS x(table_name, constraint_name, column_list, duplicate_sql)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
            JOIN pg_namespace n ON n.oid=t.relnamespace
            WHERE n.nspname='public' AND t.relname=r.table_name AND c.conname=r.constraint_name
        ) THEN
            EXECUTE r.duplicate_sql INTO duplicate_count;
            IF duplicate_count = 0 THEN
                EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I UNIQUE %s',
                    r.table_name, r.constraint_name, r.column_list);
            END IF;
        END IF;
    END LOOP;
END $$;
