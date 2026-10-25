-- Expand only: match the existing @Size(max=254) API without truncating stored addresses.
-- Older application versions can continue reading/writing their <=100-character emails.
DO $$
DECLARE
    owned_table text;
    existing_limit integer;
BEGIN
    FOREACH owned_table IN ARRAY ARRAY['users', 'pending_users'] LOOP
        SELECT character_maximum_length INTO existing_limit
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = owned_table AND column_name = 'email';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Missing owned email column on %', owned_table;
        END IF;
        -- Leave text/unbounded or already larger installations untouched; never shrink a column.
        IF existing_limit IS NOT NULL AND existing_limit < 254 THEN
            EXECUTE format('ALTER TABLE %I ALTER COLUMN email TYPE varchar(254)', owned_table);
        END IF;
    END LOOP;
END $$;
