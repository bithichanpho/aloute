-- Enforce case-insensitive username uniqueness for registered and pending accounts.
-- Existing rows are intentionally never deleted or rewritten automatically. If the preflight
-- finds duplicates, resolve them with the account owner/product policy, then rerun this migration.
DO $$
DECLARE
    duplicate_usernames TEXT;
BEGIN
    SELECT string_agg(username, ', ')
      INTO duplicate_usernames
      FROM (
          SELECT lower(username) AS username
            FROM public.users
           GROUP BY lower(username)
          HAVING count(*) > 1
           ORDER BY lower(username)
           LIMIT 20
      ) AS duplicates;

    IF duplicate_usernames IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot create case-insensitive username index: duplicate usernames in public.users (%). Resolve duplicates first; no account data was modified.', duplicate_usernames;
    END IF;

    SELECT string_agg(username, ', ')
      INTO duplicate_usernames
      FROM (
          SELECT lower(username) AS username
            FROM public.pending_users
           GROUP BY lower(username)
          HAVING count(*) > 1
           ORDER BY lower(username)
           LIMIT 20
      ) AS duplicates;

    IF duplicate_usernames IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot create case-insensitive username index: duplicate usernames in public.pending_users (%). Resolve/expire pending registrations first; no account data was modified.', duplicate_usernames;
    END IF;

    SELECT string_agg(username, ', ')
      INTO duplicate_usernames
      FROM (
          SELECT lower(u.username) AS username
            FROM public.users u
            JOIN public.pending_users p ON lower(p.username) = lower(u.username)
           GROUP BY lower(u.username)
           ORDER BY lower(u.username)
           LIMIT 20
      ) AS registered_pending_conflicts;

    IF duplicate_usernames IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot enforce username uniqueness while registered and pending accounts overlap (%). Remove or resolve stale pending registrations first; no account data was modified.', duplicate_usernames;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_lower
    ON public.users (lower(username));

CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_users_username_lower
    ON public.pending_users (lower(username));
