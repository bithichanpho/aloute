-- Read-only verification. This script performs no writes.
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
SELECT e.index_name,
       (i.indexname IS NOT NULL) AS present,
       i.indexdef
FROM expected e
LEFT JOIN pg_indexes i
  ON i.schemaname = 'public'
 AND i.tablename = 'app_document_records'
 AND i.indexname = e.index_name
ORDER BY e.index_name;

SELECT
    to_regprocedure('public.aloute_jsonb_local_datetime_key(jsonb)') AS local_datetime_key,
    to_regprocedure('public.aloute_jsonb_instant_key(jsonb)') AS instant_key,
    to_regprocedure('public.aloute_jsonb_ts(text)') AS legacy_timestamp_helper,
    to_regprocedure('public.aloute_jsonb_tstz(text)') AS legacy_timestamptz_helper,
    public.aloute_jsonb_local_datetime_key(to_jsonb('2026-10-26T12:34:56.123456789'::text)) AS local_probe,
    public.aloute_jsonb_instant_key(to_jsonb('2026-10-26T12:34:56.123456789Z'::text)) AS instant_probe;

SELECT installed_rank, version, description, success, checksum
FROM public.flyway_schema_history
WHERE version IN ('20261103','20261104','20261105','20261106','20261107','20261108','20261109','20261110')
ORDER BY installed_rank;
