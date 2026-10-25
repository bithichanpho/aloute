-- ALOUTE manual database repair (NOT a Flyway migration).
-- Run this complete file by itself in Supabase SQL Editor, wait for success,
-- and only then run 02_apply_18_optimized_indexes_no_flyway.sql.
-- This restores helper functions used by V20261106 and tests both function paths.
-- It does not edit payload rows and does not change flyway_schema_history.

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

DO $verify_helpers$
DECLARE
    local_probe integer[];
    instant_probe numeric;
BEGIN
    IF to_regprocedure('public.aloute_jsonb_local_datetime_key(jsonb)') IS NULL THEN
        RAISE EXCEPTION 'Missing public.aloute_jsonb_local_datetime_key(jsonb) after creation';
    END IF;
    IF to_regprocedure('public.aloute_jsonb_instant_key(jsonb)') IS NULL THEN
        RAISE EXCEPTION 'Missing public.aloute_jsonb_instant_key(jsonb) after creation';
    END IF;

    local_probe := public.aloute_jsonb_local_datetime_key(to_jsonb('2026-10-26T12:34:56.123456789'::text));
    IF local_probe IS NULL THEN
        RAISE EXCEPTION 'LocalDateTime helper smoke test failed';
    END IF;

    instant_probe := public.aloute_jsonb_instant_key(to_jsonb('2026-10-26T12:34:56.123456789Z'::text));
    IF instant_probe IS NULL THEN
        RAISE EXCEPTION 'Instant helper smoke test failed';
    END IF;
END;
$verify_helpers$;

SELECT
    to_regprocedure('public.aloute_jsonb_local_datetime_key(jsonb)') AS local_datetime_key,
    to_regprocedure('public.aloute_jsonb_instant_key(jsonb)') AS instant_key,
    public.aloute_jsonb_local_datetime_key(to_jsonb('2026-10-26T12:34:56.123456789'::text)) AS local_probe,
    public.aloute_jsonb_instant_key(to_jsonb('2026-10-26T12:34:56.123456789Z'::text)) AS instant_probe;
