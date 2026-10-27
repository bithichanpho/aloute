-- Read-only. Run on an approved snapshot/test database first, before V20261106.
-- No payload/content/credentials are returned. Save the count/format inventory for comparison.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT collection_name, count(*) AS documents FROM app_document_records GROUP BY collection_name ORDER BY collection_name;

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
)
SELECT collection_name,field_name,kind,COALESCE(jsonb_typeof(value),'missing') AS json_type,count(*) AS values
FROM dates GROUP BY collection_name,field_name,kind,jsonb_typeof(value) ORDER BY 1,2,4;
COMMIT;

-- For an exact before/after comparison on a quiesced snapshot, export this SELECT on each side
-- and compare files outside PostgreSQL. It returns hashes/metadata, never the payload itself:
-- SELECT collection_name,document_id,md5(payload::text),created_at,updated_at
-- FROM app_document_records ORDER BY collection_name,document_id;
