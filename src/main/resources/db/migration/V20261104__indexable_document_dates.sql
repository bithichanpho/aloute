-- Make JSONB date sorts and range filters index-backed.
--
-- SqlCriteriaCompiler used to emit NULLIF(payload ->> 'createdAt', '')::timestamp. A text to
-- timestamp cast is only STABLE, so it cannot appear in an index, and the V20261102 indexes were
-- built on the raw text instead. The expressions never matched, so every feed, profile, comment
-- and chat query scanned and sorted the whole collection.
--
-- These wrappers are declared IMMUTABLE. That is safe because the app only stores ISO-8601 values
-- (see Jackson2ObjectMapperConfig and V20261103), which parse the same under any DateStyle, and
-- Instant values always carry an explicit 'Z' offset. Values that are not ISO-8601 return NULL
-- rather than raising, so a stray legacy value can never block writes to an indexed collection.
CREATE OR REPLACE FUNCTION aloute_jsonb_ts(value text) RETURNS timestamp
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT CASE WHEN value ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}' THEN value::timestamp END $$;

CREATE OR REPLACE FUNCTION aloute_jsonb_tstz(value text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT CASE WHEN value ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}' THEN value::timestamptz END $$;

-- Replace text-based date indexes with expressions that match the generated SQL.
DROP INDEX IF EXISTS ix_doc_posts_created;
DROP INDEX IF EXISTS ix_doc_posts_user_created;
DROP INDEX IF EXISTS ix_doc_posts_community_status_created;
DROP INDEX IF EXISTS ix_doc_posts_moderation_lease;
DROP INDEX IF EXISTS ix_doc_comments_post_created;
DROP INDEX IF EXISTS ix_doc_comments_parent_created;
DROP INDEX IF EXISTS ix_doc_comments_moderation_lease;
DROP INDEX IF EXISTS ix_doc_post_revisions_post_created;
DROP INDEX IF EXISTS ix_doc_chat_conversation_sent;
DROP INDEX IF EXISTS ix_doc_chat_receiver_unread_sent;
DROP INDEX IF EXISTS ix_doc_follow_requests_target_created;
DROP INDEX IF EXISTS ix_doc_content_reports_status_created;
DROP INDEX IF EXISTS ix_doc_account_cleanup_next_attempt;
DROP INDEX IF EXISTS ix_doc_media_status_updated;

-- Posts: global feed, profile timeline / following feed, community feed, moderation scanner.
CREATE INDEX IF NOT EXISTS ix_doc_posts_created_ts
    ON app_document_records (aloute_jsonb_ts(payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_user_created_ts
    ON app_document_records ((payload ->> 'userId'), aloute_jsonb_ts(payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_community_status_created_ts
    ON app_document_records ((payload ->> 'communityId'), (payload ->> 'communityPostStatus'),
                             aloute_jsonb_ts(payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_moderation_lease_ts
    ON app_document_records ((payload ->> 'moderationStatus'), aloute_jsonb_ts(payload ->> 'moderationLeaseUntil'))
    WHERE collection_name = 'posts';

-- Comments: top-level thread, replies, moderation scanner.
CREATE INDEX IF NOT EXISTS ix_doc_comments_post_created_ts
    ON app_document_records ((payload ->> 'postId'), aloute_jsonb_ts(payload ->> 'createdAt') ASC, document_id ASC)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_parent_created_ts
    ON app_document_records ((payload ->> 'parentCommentId'), aloute_jsonb_ts(payload ->> 'createdAt') ASC, document_id ASC)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_moderation_lease_ts
    ON app_document_records ((payload ->> 'moderationStatus'), aloute_jsonb_ts(payload ->> 'moderationLeaseUntil'))
    WHERE collection_name = 'comments';

-- Reposts: profile reposts tab and reposter lists.
CREATE INDEX IF NOT EXISTS ix_doc_reposts_user_created_ts
    ON app_document_records ((payload ->> 'userId'), aloute_jsonb_ts(payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'reposts';
CREATE INDEX IF NOT EXISTS ix_doc_reposts_post_created_ts
    ON app_document_records ((payload ->> 'postId'), aloute_jsonb_ts(payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'reposts';

CREATE INDEX IF NOT EXISTS ix_doc_post_revisions_post_created_ts
    ON app_document_records ((payload ->> 'postId'), aloute_jsonb_tstz(payload ->> 'createdAt') DESC)
    WHERE collection_name = 'post_revisions';

-- Chat: conversation history, inbox latest-message scan, unread counts.
CREATE INDEX IF NOT EXISTS ix_doc_chat_conversation_sent_ts
    ON app_document_records ((payload ->> 'conversationId'), aloute_jsonb_ts(payload ->> 'sentAt') DESC, document_id DESC)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_receiver_unread_sent_ts
    ON app_document_records ((payload ->> 'receiverId'), (NULLIF(payload ->> 'read', '')::boolean),
                             aloute_jsonb_ts(payload ->> 'sentAt') DESC)
    WHERE collection_name = 'chat_messages';

-- Inbox scope is (senderId = me OR receiverId = me OR groupId IN my groups). Without these two
-- indexes PostgreSQL cannot BitmapOr the arms and reads every chat message in the system.
CREATE INDEX IF NOT EXISTS ix_doc_chat_sender
    ON app_document_records ((payload ->> 'senderId'))
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_group
    ON app_document_records ((NULLIF(payload ->> 'groupId', '')::numeric))
    WHERE collection_name = 'chat_messages';

-- Background queues and social lists.
CREATE INDEX IF NOT EXISTS ix_doc_follow_requests_target_created_ts
    ON app_document_records ((NULLIF(payload ->> 'targetId', '')::numeric), aloute_jsonb_tstz(payload ->> 'createdAt') DESC)
    WHERE collection_name = 'follow_requests';
CREATE INDEX IF NOT EXISTS ix_doc_content_reports_status_created_ts
    ON app_document_records ((payload ->> 'status'), aloute_jsonb_tstz(payload ->> 'createdAt') ASC)
    WHERE collection_name = 'content_reports';
CREATE INDEX IF NOT EXISTS ix_doc_account_cleanup_next_attempt_ts
    ON app_document_records (aloute_jsonb_tstz(payload ->> 'nextAttemptAt') ASC)
    WHERE collection_name = 'account_cleanup_jobs';
CREATE INDEX IF NOT EXISTS ix_doc_media_status_updated_ts
    ON app_document_records ((payload ->> 'status'), aloute_jsonb_ts(payload ->> 'updatedAt') ASC)
    WHERE collection_name = 'post_media_assets';

ANALYZE app_document_records;
