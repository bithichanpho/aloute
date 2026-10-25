-- Rewrite legacy JSONB date values to ISO-8601 strings.
-- Before Jackson2ObjectMapperConfig disabled WRITE_DATES_AS_TIMESTAMPS, the app wrote
-- LocalDateTime as [y, m, d, h, mi, s, nanos] arrays and Instant as epoch-second numbers.
-- Queries cast payload ->> '<field>' to timestamp/timestamptz, which fails on those values
-- ("invalid input syntax for type timestamp"). Only array/number values are touched, so the
-- migration is idempotent and leaves already-ISO rows (e.g. imported from MongoDB) unchanged.
-- Precision is kept to microseconds, the resolution of PostgreSQL timestamps.
DO $$
DECLARE
    r record;
    local_expr constant text :=
        'to_jsonb(to_char(make_timestamp((v->>0)::int, (v->>1)::int, (v->>2)::int, '
        || 'COALESCE((v->>3)::int, 0), COALESCE((v->>4)::int, 0), '
        || '(COALESCE((v->>5)::int, 0) + COALESCE((v->>6)::bigint, 0) / 1e9)::double precision), '
        || '''YYYY-MM-DD"T"HH24:MI:SS.US''))';
    instant_expr constant text :=
        'to_jsonb(to_char((to_timestamp(floor((v #>> ''{}'')::numeric)::bigint) '
        || '+ round(((v #>> ''{}'')::numeric - floor((v #>> ''{}'')::numeric)) * 1000000)::bigint '
        || '* interval ''1 microsecond'') AT TIME ZONE ''UTC'', '
        || '''YYYY-MM-DD"T"HH24:MI:SS.US"Z"''))';
BEGIN
    FOR r IN SELECT * FROM (VALUES
        -- LocalDateTime fields
        ('posts', 'createdAt', 'local'),
        ('posts', 'updatedAt', 'local'),
        ('posts', 'moderationLeaseUntil', 'local'),
        ('posts', 'moderationLastAttemptAt', 'local'),
        ('posts', 'mediaCleanupQueuedAt', 'local'),
        ('comments', 'createdAt', 'local'),
        ('comments', 'moderationLeaseUntil', 'local'),
        ('comments', 'moderationLastAttemptAt', 'local'),
        ('likes', 'createdAt', 'local'),
        ('reposts', 'createdAt', 'local'),
        ('chat_messages', 'sentAt', 'local'),
        ('chat_messages', 'deliveredAt', 'local'),
        ('post_media_assets', 'uploadedAt', 'local'),
        ('post_media_assets', 'updatedAt', 'local'),
        ('post_media_assets', 'deletedAt', 'local'),
        -- Instant fields
        ('account_security', 'pendingExpiresAt', 'instant'),
        ('account_cleanup_jobs', 'createdAt', 'instant'),
        ('account_cleanup_jobs', 'nextAttemptAt', 'instant'),
        ('post_revisions', 'createdAt', 'instant'),
        ('user_blocks', 'createdAt', 'instant'),
        ('follow_requests', 'createdAt', 'instant'),
        ('content_reports', 'createdAt', 'instant'),
        ('content_reports', 'reviewedAt', 'instant'),
        ('saved_posts', 'createdAt', 'instant'),
        ('comment_reactions', 'createdAt', 'instant')
    ) AS x(collection_name, field, kind)
    LOOP
        EXECUTE format(
            'UPDATE app_document_records d '
            || 'SET payload = jsonb_set(d.payload, ARRAY[%L], (SELECT %s FROM (SELECT d.payload -> %L AS v) s)), '
            || 'updated_at = now() '
            || 'WHERE d.collection_name = %L AND jsonb_typeof(d.payload -> %L) = %L',
            r.field,
            CASE r.kind WHEN 'local' THEN local_expr ELSE instant_expr END,
            r.field, r.collection_name, r.field,
            CASE r.kind WHEN 'local' THEN 'array' ELSE 'number' END);
    END LOOP;

    -- Post.editHistory is a nested list of PostRevision, whose createdAt is an Instant.
    EXECUTE format(
        'UPDATE app_document_records d '
        || 'SET payload = jsonb_set(d.payload, ''{editHistory}'', ('
        || '  SELECT jsonb_agg(CASE WHEN jsonb_typeof(item -> ''createdAt'') = ''number'' '
        || '    THEN jsonb_set(item, ''{createdAt}'', (SELECT %s FROM (SELECT item -> ''createdAt'' AS v) s)) '
        || '    ELSE item END ORDER BY ord) '
        || '  FROM jsonb_array_elements(d.payload -> ''editHistory'') WITH ORDINALITY AS h(item, ord))), '
        || 'updated_at = now() '
        || 'WHERE d.collection_name = ''posts'' '
        || 'AND jsonb_typeof(d.payload -> ''editHistory'') = ''array'' '
        || 'AND EXISTS (SELECT 1 FROM jsonb_array_elements(d.payload -> ''editHistory'') e '
        || '            WHERE jsonb_typeof(e -> ''createdAt'') = ''number'')',
        instant_expr);
END $$;
