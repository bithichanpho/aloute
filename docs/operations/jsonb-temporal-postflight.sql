-- Read-only; requires V20261106. Run the preflight inventory again and compare counts/hashes too.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH fields(collection_name,field_name,kind) AS (VALUES
 ('posts','createdAt','local'),('posts','updatedAt','local'),('posts','moderationLeaseUntil','local'),
 ('posts','moderationLastAttemptAt','local'),('posts','mediaCleanupQueuedAt','local'),
 ('comments','createdAt','local'),('comments','moderationLeaseUntil','local'),('comments','moderationLastAttemptAt','local'),
 ('chat_messages','sentAt','local'),('chat_messages','deliveredAt','local'),('likes','createdAt','local'),('reposts','createdAt','local'),
 ('post_media_assets','uploadedAt','local'),('post_media_assets','updatedAt','local'),('post_media_assets','deletedAt','local'),
 ('account_cleanup_jobs','createdAt','instant'),('account_cleanup_jobs','nextAttemptAt','instant'),
 ('account_security','pendingExpiresAt','instant'),('comment_reactions','createdAt','instant'),
 ('content_reports','createdAt','instant'),('content_reports','reviewedAt','instant'),('follow_requests','createdAt','instant'),
 ('post_revisions','createdAt','instant'),('saved_posts','createdAt','instant'),('user_blocks','createdAt','instant')
), dates AS (
 SELECT f.collection_name,f.field_name,f.kind,d.payload->f.field_name AS value
 FROM fields f JOIN app_document_records d USING(collection_name)
 UNION ALL
 SELECT 'posts','editHistory[].createdAt','instant',r.item->'createdAt'
 FROM app_document_records d CROSS JOIN LATERAL jsonb_array_elements(
   CASE WHEN jsonb_typeof(d.payload->'editHistory')='array' THEN d.payload->'editHistory' ELSE '[]'::jsonb END) r(item)
 WHERE d.collection_name='posts'
), checked AS (
 SELECT *, CASE WHEN value IS NULL THEN 'missing' WHEN value='null'::jsonb THEN 'null'
   WHEN kind='local' AND aloute_jsonb_local_datetime_key(value) IS NOT NULL THEN 'valid'
   WHEN kind='instant' AND aloute_jsonb_instant_key(value) IS NOT NULL THEN 'valid' ELSE 'malformed' END AS state
 FROM dates
)
SELECT collection_name,field_name,kind,state,count(*) AS values FROM checked GROUP BY 1,2,3,4 ORDER BY 1,2,4;

SELECT indexname,indexdef FROM pg_indexes WHERE tablename='app_document_records' AND indexname LIKE '%_v2' ORDER BY indexname;
COMMIT;
-- Investigate malformed rows with the owning module. Do not invent timestamps or replace/delete payloads.
