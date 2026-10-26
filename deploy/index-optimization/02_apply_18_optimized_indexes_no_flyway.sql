-- ALOUTE index optimization, adapted to the CASO SqlCriteriaCompiler.
-- Run ONLY AFTER 01_restore_jsonb_temporal_helpers.sql completed successfully.
-- This script is outside Flyway: it does not add a migration version/history row.
-- CREATE INDEX may block writes during build; use a quiet window on a large table.

DO $check_table$
BEGIN
    IF to_regclass('public.app_document_records') IS NULL THEN
        RAISE EXCEPTION 'Required table public.app_document_records does not exist. Apply the existing V20261102 migration first.';
    END IF;
    IF to_regprocedure('public.aloute_jsonb_local_datetime_key(jsonb)') IS NULL THEN
        RAISE EXCEPTION 'Run 01_restore_jsonb_temporal_helpers.sql first: local datetime helper is missing.';
    END IF;
    IF to_regprocedure('public.aloute_jsonb_instant_key(jsonb)') IS NULL THEN
        RAISE EXCEPTION 'Run 01_restore_jsonb_temporal_helpers.sql first: instant helper is missing.';
    END IF;
END;
$check_table$;

-- 14 indexes from the temporal-query optimization in V20261106.
CREATE INDEX IF NOT EXISTS ix_doc_posts_created_v2 ON public.app_document_records
    (public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_user_created_v2 ON public.app_document_records
    ((payload ->> 'userId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_community_created_v2 ON public.app_document_records
    ((payload ->> 'communityId'), (payload ->> 'communityPostStatus'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_moderation_lease_v2 ON public.app_document_records
    ((payload ->> 'moderationStatus'), public.aloute_jsonb_local_datetime_key(payload -> 'moderationLeaseUntil'))
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_comments_post_created_v2 ON public.app_document_records
    ((payload ->> 'postId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt'), document_id)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_parent_created_v2 ON public.app_document_records
    ((payload ->> 'parentCommentId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt'))
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_moderation_lease_v2 ON public.app_document_records
    ((payload ->> 'moderationStatus'), public.aloute_jsonb_local_datetime_key(payload -> 'moderationLeaseUntil'))
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_post_revisions_created_v2 ON public.app_document_records
    ((payload ->> 'postId'), public.aloute_jsonb_instant_key(payload -> 'createdAt') DESC)
    WHERE collection_name = 'post_revisions';
CREATE INDEX IF NOT EXISTS ix_doc_chat_conversation_sent_v2 ON public.app_document_records
    ((payload ->> 'conversationId'), public.aloute_jsonb_local_datetime_key(payload -> 'sentAt') DESC, document_id DESC)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_receiver_unread_sent_v2 ON public.app_document_records
    ((payload ->> 'receiverId'), (NULLIF(payload ->> 'read','')::boolean), public.aloute_jsonb_local_datetime_key(payload -> 'sentAt') DESC)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_follow_requests_created_v2 ON public.app_document_records
    ((NULLIF(payload ->> 'targetId','')::numeric), public.aloute_jsonb_instant_key(payload -> 'createdAt') DESC)
    WHERE collection_name = 'follow_requests';
CREATE INDEX IF NOT EXISTS ix_doc_reports_created_v2 ON public.app_document_records
    ((payload ->> 'status'), public.aloute_jsonb_instant_key(payload -> 'createdAt'))
    WHERE collection_name = 'content_reports';
CREATE INDEX IF NOT EXISTS ix_doc_account_cleanup_attempt_v2 ON public.app_document_records
    (public.aloute_jsonb_instant_key(payload -> 'nextAttemptAt'))
    WHERE collection_name = 'account_cleanup_jobs';
CREATE INDEX IF NOT EXISTS ix_doc_media_status_updated_v2 ON public.app_document_records
    ((payload ->> 'status'), public.aloute_jsonb_local_datetime_key(payload -> 'updatedAt'))
    WHERE collection_name = 'post_media_assets';

-- Four supplemental indexes adapted to the merged CASO query paths.
CREATE INDEX IF NOT EXISTS ix_doc_reposts_user_created_v2 ON public.app_document_records
    ((payload ->> 'userId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'reposts';
CREATE INDEX IF NOT EXISTS ix_doc_reposts_post_created_v2 ON public.app_document_records
    ((payload ->> 'postId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'reposts';
CREATE INDEX IF NOT EXISTS ix_doc_chat_sender ON public.app_document_records ((payload ->> 'senderId'))
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_group ON public.app_document_records ((NULLIF(payload ->> 'groupId', '')::numeric))
    WHERE collection_name = 'chat_messages';

ANALYZE public.app_document_records;

-- Result summary. Expected present_count = 18 and missing_count = 0.
WITH expected(index_name) AS (
    VALUES
      ('ix_doc_posts_created_v2'),
      ('ix_doc_posts_user_created_v2'),
      ('ix_doc_posts_community_created_v2'),
      ('ix_doc_posts_moderation_lease_v2'),
      ('ix_doc_comments_post_created_v2'),
      ('ix_doc_comments_parent_created_v2'),
      ('ix_doc_comments_moderation_lease_v2'),
      ('ix_doc_reposts_user_created_v2'),
      ('ix_doc_reposts_post_created_v2'),
      ('ix_doc_post_revisions_created_v2'),
      ('ix_doc_chat_conversation_sent_v2'),
      ('ix_doc_chat_receiver_unread_sent_v2'),
      ('ix_doc_chat_sender'),
      ('ix_doc_chat_group'),
      ('ix_doc_follow_requests_created_v2'),
      ('ix_doc_reports_created_v2'),
      ('ix_doc_account_cleanup_attempt_v2'),
      ('ix_doc_media_status_updated_v2')
)
SELECT COUNT(i.indexname) AS present_count,
       COUNT(*) - COUNT(i.indexname) AS missing_count
FROM expected e
LEFT JOIN pg_indexes i
  ON i.schemaname = 'public'
 AND i.tablename = 'app_document_records'
 AND i.indexname = e.index_name;
