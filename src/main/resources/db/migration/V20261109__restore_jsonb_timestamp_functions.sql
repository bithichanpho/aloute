-- Restore robust JSON timestamp helpers used by indexed document queries.
-- Forward-only: do not edit V20261104 because it may already exist in shared DB history.
-- Invalid/non-ISO values return NULL instead of aborting feed or moderation queries.

CREATE OR REPLACE FUNCTION public.aloute_jsonb_ts(value text)
RETURNS timestamp without time zone
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $function$
BEGIN
    IF value IS NULL OR value !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}' THEN
        RETURN NULL;
    END IF;

    BEGIN
        RETURN value::timestamp without time zone;
    EXCEPTION WHEN OTHERS THEN
        RETURN NULL;
    END;
END;
$function$;

CREATE OR REPLACE FUNCTION public.aloute_jsonb_tstz(value text)
RETURNS timestamp with time zone
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $function$
BEGIN
    -- Instant values must contain an explicit UTC/offset suffix so the result is stable
    -- regardless of the database session TimeZone.
    IF value IS NULL
       OR value !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*(Z|[+-]\d{2}:?\d{2})$' THEN
        RETURN NULL;
    END IF;

    BEGIN
        RETURN value::timestamp with time zone;
    EXCEPTION WHEN OTHERS THEN
        RETURN NULL;
    END;
END;
$function$;
