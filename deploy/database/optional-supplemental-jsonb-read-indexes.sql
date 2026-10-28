-- ALOUTE supplemental indexes for the merged application (idempotent)
--
-- Run once in the Supabase SQL Editor AFTER Flyway migration V20261106 has succeeded.
-- This is deliberately outside src/main/resources/db/migration: do not edit historical
-- migrations V20261103/V20261104/V20261106, whose checksums already exist in Flyway history.
-- It complements, rather than replaces, the 18 optimize-branch indexes in V20261104 and
-- the v2 temporal indexes in V20261106.
--
-- These expressions match SqlCriteriaCompiler in this merged source tree, which sorts
-- LocalDateTime/Instant through aloute_jsonb_local_datetime_key(jsonb) and uses a literal
-- collection_name predicate in PostgresDocumentStore.find().

DO $$
BEGIN
    IF to_regclass('public.app_document_records') IS NULL THEN
        RAISE EXCEPTION 'public.app_document_records is missing; run the application migrations first';
    END IF;
    IF to_regprocedure('public.aloute_jsonb_local_datetime_key(jsonb)') IS NULL THEN
        RAISE EXCEPTION 'Missing public.aloute_jsonb_local_datetime_key(jsonb); ensure V20261106__jsonb_temporal_query_keys.sql succeeded before applying this script';
    END IF;
END $$;

-- Community timeline: filters by communityId and orders by createdAt DESC, document_id DESC.
-- Separate from the status-specific V20261106 index so the mixed NULL/APPROVED path can scan
-- the requested community in timeline order and filter status without a full global sort.
CREATE INDEX IF NOT EXISTS ix_doc_posts_community_feed_v3
    ON public.app_document_records (
        (payload ->> 'communityId'),
        public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC,
        document_id DESC
    )
    WHERE collection_name = 'posts';

-- Root-comment timeline; the predicate intentionally mirrors Criteria.where(parentCommentId).is(null).
CREATE INDEX IF NOT EXISTS ix_doc_comments_root_post_created_v3
    ON public.app_document_records (
        (payload ->> 'postId'),
        public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') ASC,
        document_id ASC
    )
    WHERE collection_name = 'comments'
      AND (NOT jsonb_exists(payload, 'parentCommentId')
           OR payload -> 'parentCommentId' = 'null'::jsonb);

-- Repost timelines: V20261104 has the legacy text-timestamp variants; the merged compiler uses
-- the v2 JSONB timestamp key, so these indexes make that exact expression indexable.
CREATE INDEX IF NOT EXISTS ix_doc_reposts_user_created_v2
    ON public.app_document_records (
        (payload ->> 'userId'),
        public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC,
        document_id DESC
    )
    WHERE collection_name = 'reposts';

CREATE INDEX IF NOT EXISTS ix_doc_reposts_post_created_v2
    ON public.app_document_records (
        (payload ->> 'postId'),
        public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC,
        document_id DESC
    )
    WHERE collection_name = 'reposts';

-- Repost lookup by userId + postId is also a hot exact-match path.
CREATE INDEX IF NOT EXISTS ix_doc_reposts_user_post_v2
    ON public.app_document_records ((payload ->> 'userId'), (payload ->> 'postId'))
    WHERE collection_name = 'reposts';

-- Chat paths: conversation history is already covered by V20261106; these support the other
-- repository lookups, idempotent sends, call-history lookup, and sender/receiver timeline OR.
CREATE INDEX IF NOT EXISTS ix_doc_chat_sender_client_v2
    ON public.app_document_records ((payload ->> 'senderId'), (payload ->> 'clientId'))
    WHERE collection_name = 'chat_messages';

CREATE INDEX IF NOT EXISTS ix_doc_chat_call_id_v2
    ON public.app_document_records ((payload ->> 'callId'))
    WHERE collection_name = 'chat_messages';

CREATE INDEX IF NOT EXISTS ix_doc_chat_sender_sent_v2
    ON public.app_document_records (
        (payload ->> 'senderId'),
        public.aloute_jsonb_local_datetime_key(payload -> 'sentAt') DESC,
        document_id DESC
    )
    WHERE collection_name = 'chat_messages';

CREATE INDEX IF NOT EXISTS ix_doc_chat_receiver_sent_v2
    ON public.app_document_records (
        (payload ->> 'receiverId'),
        public.aloute_jsonb_local_datetime_key(payload -> 'sentAt') DESC,
        document_id DESC
    )
    WHERE collection_name = 'chat_messages';

CREATE INDEX IF NOT EXISTS ix_doc_chat_receiver_sender_read_v2
    ON public.app_document_records (
        (payload ->> 'receiverId'),
        (payload ->> 'senderId'),
        (NULLIF(payload ->> 'read', '')::boolean)
    )
    WHERE collection_name = 'chat_messages';

ANALYZE public.app_document_records;

-- Verify after execution:
-- SELECT indexname FROM pg_indexes WHERE schemaname='public'
--   AND tablename='app_document_records' AND indexname LIKE '%_v2' OR indexname LIKE '%_v3'
-- ORDER BY indexname;
