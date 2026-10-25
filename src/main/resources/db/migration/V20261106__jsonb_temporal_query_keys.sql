-- Expand only: payloads, metadata timestamps and old indexes are deliberately unchanged.
-- PostgreSQL timestamp has microsecond precision. An integer tuple preserves all Java nanoseconds
-- and wall-clock LocalDateTime semantics (no session timezone or DateStyle conversion).
CREATE FUNCTION aloute_jsonb_local_datetime_key(value jsonb) RETURNS integer[]
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
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
END $$;

-- Jackson's existing mapper uses epoch seconds (including fractional nanoseconds) for Instant.
-- ISO offsets are converted to UTC arithmetically; no timestamptz cast can round the fraction.
CREATE FUNCTION aloute_jsonb_instant_key(value jsonb) RETURNS numeric
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
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
        -- Instant extends one leap year beyond LocalDateTime at each end.
        local_parts := aloute_jsonb_local_datetime_key(to_jsonb(regexp_replace(parts[1], '^[+-]?[0-9]+-', '0000-')));
    ELSE local_parts := aloute_jsonb_local_datetime_key(to_jsonb(parts[1])); END IF;
    IF local_parts IS NULL THEN RETURN NULL; END IF;
    IF parts[2] <> 'Z' THEN
        IF parts[4]::integer > 18 OR parts[5]::integer > 59 OR COALESCE(parts[6]::integer,0) > 59 THEN RETURN NULL; END IF;
        offset_seconds := parts[4]::integer * 3600 + parts[5]::integer * 60 + COALESCE(parts[6]::integer,0);
        IF offset_seconds > 64800 THEN RETURN NULL; END IF;
        IF parts[3] = '-' THEN offset_seconds := -offset_seconds; END IF;
    END IF;
    -- Proleptic Gregorian epoch-day calculation, matching java.time.LocalDate.toEpochDay().
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
END $$;

-- Matching expression indexes. Retain the original text indexes for older application instances.
CREATE INDEX ix_doc_posts_created_v2 ON app_document_records
    (aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC) WHERE collection_name = 'posts';
CREATE INDEX ix_doc_posts_user_created_v2 ON app_document_records
    ((payload ->> 'userId'), aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC, document_id DESC) WHERE collection_name = 'posts';
CREATE INDEX ix_doc_posts_community_created_v2 ON app_document_records
    ((payload ->> 'communityId'), (payload ->> 'communityPostStatus'), aloute_jsonb_local_datetime_key(payload -> 'createdAt') DESC) WHERE collection_name = 'posts';
CREATE INDEX ix_doc_posts_moderation_lease_v2 ON app_document_records
    ((payload ->> 'moderationStatus'), aloute_jsonb_local_datetime_key(payload -> 'moderationLeaseUntil')) WHERE collection_name = 'posts';
CREATE INDEX ix_doc_comments_post_created_v2 ON app_document_records
    ((payload ->> 'postId'), aloute_jsonb_local_datetime_key(payload -> 'createdAt'), document_id) WHERE collection_name = 'comments';
CREATE INDEX ix_doc_comments_parent_created_v2 ON app_document_records
    ((payload ->> 'parentCommentId'), aloute_jsonb_local_datetime_key(payload -> 'createdAt')) WHERE collection_name = 'comments';
CREATE INDEX ix_doc_comments_moderation_lease_v2 ON app_document_records
    ((payload ->> 'moderationStatus'), aloute_jsonb_local_datetime_key(payload -> 'moderationLeaseUntil')) WHERE collection_name = 'comments';
CREATE INDEX ix_doc_post_revisions_created_v2 ON app_document_records
    ((payload ->> 'postId'), aloute_jsonb_instant_key(payload -> 'createdAt') DESC) WHERE collection_name = 'post_revisions';
CREATE INDEX ix_doc_chat_conversation_sent_v2 ON app_document_records
    ((payload ->> 'conversationId'), aloute_jsonb_local_datetime_key(payload -> 'sentAt') DESC, document_id DESC) WHERE collection_name = 'chat_messages';
CREATE INDEX ix_doc_chat_receiver_unread_sent_v2 ON app_document_records
    ((payload ->> 'receiverId'), (NULLIF(payload ->> 'read','')::boolean), aloute_jsonb_local_datetime_key(payload -> 'sentAt') DESC) WHERE collection_name = 'chat_messages';
CREATE INDEX ix_doc_follow_requests_created_v2 ON app_document_records
    ((NULLIF(payload ->> 'targetId','')::numeric), aloute_jsonb_instant_key(payload -> 'createdAt') DESC) WHERE collection_name = 'follow_requests';
CREATE INDEX ix_doc_reports_created_v2 ON app_document_records
    ((payload ->> 'status'), aloute_jsonb_instant_key(payload -> 'createdAt')) WHERE collection_name = 'content_reports';
CREATE INDEX ix_doc_account_cleanup_attempt_v2 ON app_document_records
    (aloute_jsonb_instant_key(payload -> 'nextAttemptAt')) WHERE collection_name = 'account_cleanup_jobs';
CREATE INDEX ix_doc_media_status_updated_v2 ON app_document_records
    ((payload ->> 'status'), aloute_jsonb_local_datetime_key(payload -> 'updatedAt')) WHERE collection_name = 'post_media_assets';
