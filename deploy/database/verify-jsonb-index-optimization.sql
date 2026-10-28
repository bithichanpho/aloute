-- Verify optimizer helpers and selected index definitions in the active schema.
SELECT
    to_regclass('public.app_document_records') AS document_store_table,
    to_regprocedure('public.aloute_jsonb_ts(text)') AS legacy_local_timestamp_helper,
    to_regprocedure('public.aloute_jsonb_tstz(text)') AS legacy_instant_timestamp_helper,
    to_regprocedure('public.aloute_jsonb_local_datetime_key(jsonb)') AS local_datetime_v2_helper,
    to_regprocedure('public.aloute_jsonb_instant_key(jsonb)') AS instant_v2_helper;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'app_document_records'
  AND (
    indexname LIKE 'ix_doc_posts_%created%'
    OR indexname LIKE 'ix_doc_comments_%created%'
    OR indexname LIKE 'ix_doc_reposts_%'
    OR indexname LIKE 'ix_doc_chat_%'
  )
ORDER BY indexname;

SELECT relname AS table_name, n_live_tup AS estimated_rows
FROM pg_stat_user_tables
WHERE schemaname = 'public' AND relname = 'app_document_records';
