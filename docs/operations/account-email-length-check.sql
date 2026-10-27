-- Read-only preflight AND postflight. Run against the intended deployment only via its normal DBA process.
-- Save the preflight counts; compare again after Flyway V20261108. No addresses/passwords are returned.
SELECT table_name, data_type, character_maximum_length
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name IN ('users', 'pending_users') AND column_name = 'email'
ORDER BY table_name;

SELECT 'users' AS owner, count(*) AS rows, max(char_length(email)) AS longest_email,
       count(*) FILTER (WHERE char_length(email)>254) AS over_api_limit
FROM users
UNION ALL
SELECT 'pending_users', count(*), max(char_length(email)),
       count(*) FILTER (WHERE char_length(email)>254)
FROM pending_users;

-- Existing installations that already allow >254 characters or text remain wider: never shrink.
-- Keep existing unique constraints/indexes and verify they are still valid after expansion.
SELECT t.relname AS owner, i.relname AS index_name, x.indisunique, x.indisvalid
FROM pg_index x
JOIN pg_class t ON t.oid=x.indrelid
JOIN pg_class i ON i.oid=x.indexrelid
JOIN pg_namespace n ON n.oid=t.relnamespace
WHERE n.nspname=current_schema() AND t.relname IN ('users','pending_users')
ORDER BY t.relname, i.relname;
