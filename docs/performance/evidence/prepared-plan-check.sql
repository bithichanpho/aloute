-- Test/snapshot only. READ ONLY, session-local planner setting, no schema/data writes.
BEGIN READ ONLY;
SET LOCAL plan_cache_mode=force_generic_plan;
PREPARE aloute_perf_parameterized(text,bigint,text) AS
SELECT d.payload::text FROM app_document_records d
WHERE d.collection_name=$1 AND aloute_jsonb_local_datetime_key(d.payload->'createdAt') IS NOT NULL
AND (d.payload->>'userId'=$3 OR d.payload->>'userId' IN
 (SELECT value FROM aloute_read_social_following_authors WHERE viewer_id=$2))
ORDER BY aloute_jsonb_local_datetime_key(d.payload->'createdAt') DESC,d.document_id DESC LIMIT 50;
EXPLAIN (ANALYZE,BUFFERS) EXECUTE aloute_perf_parameterized('posts',1,'1');
PREPARE aloute_perf_owned_collection(bigint,text) AS
SELECT d.payload::text FROM app_document_records d
WHERE d.collection_name='posts' AND aloute_jsonb_local_datetime_key(d.payload->'createdAt') IS NOT NULL
AND (d.payload->>'userId'=$2 OR d.payload->>'userId' IN
 (SELECT value FROM aloute_read_social_following_authors WHERE viewer_id=$1))
ORDER BY aloute_jsonb_local_datetime_key(d.payload->'createdAt') DESC,d.document_id DESC LIMIT 50;
EXPLAIN (ANALYZE,BUFFERS) EXECUTE aloute_perf_owned_collection(1,'1');
DO $$
DECLARE line record; plan_text text := '';
BEGIN
  FOR line IN EXECUTE 'EXPLAIN EXECUTE aloute_perf_owned_collection(1,''1'')' LOOP
    plan_text := plan_text || line."QUERY PLAN" || E'\n';
  END LOOP;
  IF position('ix_doc_posts_created_v2' IN plan_text)=0 OR position('Sort' IN plan_text)>0 THEN
    RAISE EXCEPTION 'Expected the owned collection generic plan to use the ordered partial index: %',plan_text;
  END IF;
END $$;
DEALLOCATE aloute_perf_parameterized;
DEALLOCATE aloute_perf_owned_collection;
ROLLBACK;
