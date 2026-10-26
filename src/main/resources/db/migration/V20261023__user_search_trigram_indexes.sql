-- User search runs `lower(username) LIKE '%term%' OR lower(full_name) LIKE '%term%'`.
-- A leading wildcard cannot use a btree index, so every search scanned the whole users table.
-- pg_trgm GIN indexes on the exact expressions the query uses make it an index lookup.
--
-- Notes:
--   * pg_trgm is a "trusted" extension on PostgreSQL 13+, so the database owner can create it.
--     On managed hosts that restrict extensions, enable pg_trgm once from the provider console.
--   * Terms shorter than 3 characters have no trigrams and still scan; the API enforces a
--     minimum of 2 characters, so those short searches stay a sequential scan.
--   * Plain CREATE INDEX takes a short write lock on users. On a very large table, create the
--     indexes beforehand with CREATE INDEX CONCURRENTLY (same names); IF NOT EXISTS then skips them.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_users_username_lower_trgm
    ON public.users USING gin (lower(username) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_full_name_lower_trgm
    ON public.users USING gin (lower(full_name) gin_trgm_ops);
