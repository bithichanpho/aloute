-- Brings every database to the same JSONB helper and index set, whichever history it has.
--
-- 1. Helpers: aloute_jsonb_instant_key called aloute_jsonb_local_datetime_key unqualified, which
--    fails with "function aloute_jsonb_local_datetime_key(jsonb) does not exist" whenever the
--    caller's search_path lacks public. Pin search_path and qualify the call; same definitions as
--    deploy/index-optimization/01_restore_jsonb_temporal_helpers.sql.
-- 2. Indexes: the V20261104 that creates the aloute_jsonb_ts/_tstz indexes only ran on fresh
--    databases. SqlCriteriaCompiler queries through the V20261106 key functions, so those *_ts
--    indexes are never used and only slow down writes. That script also dropped the original
--    V20261102 text indexes, which V20261106 deliberately keeps for older application instances.
-- 3. The four supplemental indexes from deploy/index-optimization/02_apply_18_optimized_indexes_no_flyway.sql,
--    with the same names, so a database where that script already ran is unchanged.
-- No payload is read or changed here.
CREATE OR REPLACE FUNCTION public.aloute_jsonb_local_datetime_key(value jsonb)
RETURNS integer[]
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog, public
AS $$
DECLARE
    parts text[];
    result integer[];
    item jsonb;
    days integer;
BEGIN
    IF jsonb_typeof(value) = 'array' THEN
        IF jsonb_array_length(value) NOT BETWEEN 5 AND 7 THEN RETURN NULL; END IF;
        FOR item IN SELECT jsonb_array_elements(value) LOOP
            IF jsonb_typeof(item) <> 'number' OR item::text !~ '^-?[0-9]+$' THEN RETURN NULL; END IF;
        END LOOP;
        SELECT array_agg(v::integer ORDER BY n) INTO result
        FROM jsonb_array_elements_text(value) WITH ORDINALITY AS a(v,n);
        IF cardinality(result) = 5 THEN result := result || ARRAY[0,0];
        ELSIF cardinality(result) = 6 THEN result := result || ARRAY[0]; END IF;
    ELSIF jsonb_typeof(value) = 'string' THEN
        parts := regexp_match(value #>> '{}',
            '^([0-9]{4}|-[0-9]{4,9}|[+][0-9]{5,9})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?$');
        IF parts IS NULL THEN RETURN NULL; END IF;
        IF parts[1] ~ '^-0+$' THEN RETURN NULL; END IF;
        result := ARRAY[parts[1]::integer, parts[2]::integer, parts[3]::integer,
            parts[4]::integer, parts[5]::integer, COALESCE(parts[6]::integer,0),
            COALESCE(rpad(parts[7],9,'0')::integer,0)];
    ELSE RETURN NULL;
    END IF;
    IF result[1] NOT BETWEEN -999999999 AND 999999999
        OR result[2] NOT BETWEEN 1 AND 12 OR result[3] < 1
        OR result[4] NOT BETWEEN 0 AND 23 OR result[5] NOT BETWEEN 0 AND 59
        OR result[6] NOT BETWEEN 0 AND 59 OR result[7] NOT BETWEEN 0 AND 999999999
    THEN RETURN NULL; END IF;
    days := CASE result[2] WHEN 2 THEN
        CASE WHEN result[1] % 4 = 0 AND (result[1] % 100 <> 0 OR result[1] % 400 = 0) THEN 29 ELSE 28 END
        WHEN 4 THEN 30 WHEN 6 THEN 30 WHEN 9 THEN 30 WHEN 11 THEN 30 ELSE 31 END;
    IF result[3] > days THEN RETURN NULL; END IF;
    RETURN result;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RETURN NULL;
END;
$$;

-- Recreate the instant key with an explicit public-qualified dependency on the helper.
-- Its mapping semantics are preserved from V20261106; this changes no table payloads.
CREATE OR REPLACE FUNCTION public.aloute_jsonb_instant_key(value jsonb)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog, public
AS $$
DECLARE
    parts text[];
    local_parts integer[];
    year bigint;
    day_count bigint;
    offset_seconds integer := 0;
    result numeric;
BEGIN
    IF jsonb_typeof(value) = 'number' THEN
        result := (value::text)::numeric;
        IF scale(result) > 9 OR result < -31557014167219200 OR result >= 31556889864403200 THEN RETURN NULL; END IF;
        RETURN result;
    END IF;
    IF jsonb_typeof(value) <> 'string' THEN RETURN NULL; END IF;
    parts := regexp_match(value #>> '{}',
        '^((?:[0-9]{4}|-[0-9]{4,10}|[+][0-9]{5,10})-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?)(Z|([+-])([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?)$');
    IF parts IS NULL THEN RETURN NULL; END IF;
    year := (regexp_match(parts[1], '^([+-]?[0-9]+)-'))[1]::bigint;
    IF year NOT BETWEEN -1000000000 AND 1000000000 THEN RETURN NULL; END IF;
    IF abs(year) = 1000000000 THEN
        local_parts := public.aloute_jsonb_local_datetime_key(to_jsonb(regexp_replace(parts[1], '^[+-]?[0-9]+-', '0000-')));
    ELSE
        local_parts := public.aloute_jsonb_local_datetime_key(to_jsonb(parts[1]));
    END IF;
    IF local_parts IS NULL THEN RETURN NULL; END IF;
    IF parts[2] <> 'Z' THEN
        IF parts[4]::integer > 18 OR parts[5]::integer > 59 OR COALESCE(parts[6]::integer,0) > 59 THEN RETURN NULL; END IF;
        offset_seconds := parts[4]::integer * 3600 + parts[5]::integer * 60 + COALESCE(parts[6]::integer,0);
        IF offset_seconds > 64800 THEN RETURN NULL; END IF;
        IF parts[3] = '-' THEN offset_seconds := -offset_seconds; END IF;
    END IF;
    day_count := 365 * year;
    IF year >= 0 THEN day_count := day_count + (year+3)/4 - (year+99)/100 + (year+399)/400;
    ELSE day_count := day_count - (year/(-4) - year/(-100) + year/(-400)); END IF;
    day_count := day_count + (367 * local_parts[2] - 362)/12 + local_parts[3] - 1;
    IF local_parts[2] > 2 THEN
        day_count := day_count - CASE WHEN year % 4 = 0 AND (year % 100 <> 0 OR year % 400 = 0) THEN 1 ELSE 2 END;
    END IF;
    result := (day_count - 719528) * 86400 + local_parts[4] * 3600 + local_parts[5] * 60
        + local_parts[6] - offset_seconds + local_parts[7]::numeric / 1000000000;
    IF result < -31557014167219200 OR result >= 31556889864403200 THEN RETURN NULL; END IF;
    RETURN result;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RETURN NULL;
END;
$$;

DROP INDEX IF EXISTS ix_doc_posts_created_ts;
DROP INDEX IF EXISTS ix_doc_posts_user_created_ts;
DROP INDEX IF EXISTS ix_doc_posts_community_status_created_ts;
DROP INDEX IF EXISTS ix_doc_posts_moderation_lease_ts;
DROP INDEX IF EXISTS ix_doc_comments_post_created_ts;
DROP INDEX IF EXISTS ix_doc_comments_parent_created_ts;
DROP INDEX IF EXISTS ix_doc_comments_moderation_lease_ts;
DROP INDEX IF EXISTS ix_doc_reposts_user_created_ts;
DROP INDEX IF EXISTS ix_doc_reposts_post_created_ts;
DROP INDEX IF EXISTS ix_doc_post_revisions_post_created_ts;
DROP INDEX IF EXISTS ix_doc_chat_conversation_sent_ts;
DROP INDEX IF EXISTS ix_doc_chat_receiver_unread_sent_ts;
DROP INDEX IF EXISTS ix_doc_follow_requests_target_created_ts;
DROP INDEX IF EXISTS ix_doc_content_reports_status_created_ts;
DROP INDEX IF EXISTS ix_doc_account_cleanup_next_attempt_ts;
DROP INDEX IF EXISTS ix_doc_media_status_updated_ts;

CREATE INDEX IF NOT EXISTS ix_doc_posts_created
    ON app_document_records ((payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_user_created
    ON app_document_records ((payload ->> 'userId'), (payload ->> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_community_status_created
    ON app_document_records ((payload ->> 'communityId'), (payload ->> 'communityPostStatus'), (payload ->> 'createdAt') DESC)
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_posts_moderation_lease
    ON app_document_records ((payload ->> 'moderationStatus'), (payload ->> 'moderationLeaseUntil'))
    WHERE collection_name = 'posts';
CREATE INDEX IF NOT EXISTS ix_doc_comments_post_created
    ON app_document_records ((payload ->> 'postId'), (payload ->> 'createdAt') ASC, document_id ASC)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_parent_created
    ON app_document_records ((payload ->> 'parentCommentId'), (payload ->> 'createdAt') ASC)
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_comments_moderation_lease
    ON app_document_records ((payload ->> 'moderationStatus'), (payload ->> 'moderationLeaseUntil'))
    WHERE collection_name = 'comments';
CREATE INDEX IF NOT EXISTS ix_doc_post_revisions_post_created
    ON app_document_records ((payload ->> 'postId'), (payload ->> 'createdAt') DESC)
    WHERE collection_name = 'post_revisions';
CREATE INDEX IF NOT EXISTS ix_doc_chat_conversation_sent
    ON app_document_records ((payload ->> 'conversationId'), (payload ->> 'sentAt') DESC, document_id DESC)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_receiver_unread_sent
    ON app_document_records ((payload ->> 'receiverId'), (NULLIF(payload ->> 'read', '')::boolean), (payload ->> 'sentAt') DESC)
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_follow_requests_target_created
    ON app_document_records ((NULLIF(payload ->> 'targetId', '')::numeric), (payload ->> 'createdAt') DESC)
    WHERE collection_name = 'follow_requests';
CREATE INDEX IF NOT EXISTS ix_doc_content_reports_status_created
    ON app_document_records ((payload ->> 'status'), (payload ->> 'createdAt') ASC)
    WHERE collection_name = 'content_reports';
CREATE INDEX IF NOT EXISTS ix_doc_account_cleanup_next_attempt
    ON app_document_records ((payload ->> 'nextAttemptAt') ASC)
    WHERE collection_name = 'account_cleanup_jobs';
CREATE INDEX IF NOT EXISTS ix_doc_media_status_updated
    ON app_document_records ((payload ->> 'status'), (payload ->> 'updatedAt') ASC)
    WHERE collection_name = 'post_media_assets';

-- Reposts were left out of V20261106: profile reposts tab and reposter lists.
CREATE INDEX IF NOT EXISTS ix_doc_reposts_user_created_v2
    ON app_document_records ((payload ->> 'userId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'reposts';
CREATE INDEX IF NOT EXISTS ix_doc_reposts_post_created_v2
    ON app_document_records ((payload ->> 'postId'), public.aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC)
    WHERE collection_name = 'reposts';

-- Inbox scope is (senderId = me OR receiverId = me OR groupId IN my groups). Without these two
-- indexes PostgreSQL cannot BitmapOr the arms and reads every chat message in the system.
CREATE INDEX IF NOT EXISTS ix_doc_chat_sender
    ON app_document_records ((payload ->> 'senderId'))
    WHERE collection_name = 'chat_messages';
CREATE INDEX IF NOT EXISTS ix_doc_chat_group
    ON app_document_records ((NULLIF(payload ->> 'groupId', '')::numeric))
    WHERE collection_name = 'chat_messages';

ANALYZE app_document_records;
