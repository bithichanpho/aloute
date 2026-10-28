-- ALOUTE: re-run moderation for one existing post after deploying the updated moderation service.
-- Step 1: find the post ID first; confirm its current status/content before changing anything.
SELECT
    document_id AS post_id,
    payload->>'moderationStatus' AS moderation_status,
    payload->>'toxicityScore' AS toxicity_score,
    payload->>'moderationAttempts' AS moderation_attempts,
    payload->>'moderationFailureReason' AS moderation_failure_reason,
    left(coalesce(payload->>'content', ''), 180) AS content_preview,
    updated_at
FROM public.app_document_records
WHERE collection_name = 'posts'
  AND payload->>'content' ILIKE '%fuck you%'
ORDER BY created_at DESC
LIMIT 20;

-- Step 2: replace PASTE_POST_ID_HERE with the exact ID from the first query.
-- This only requeues a post currently marked APPROVED (or a legacy post with no status).
-- It does not delete the post or change its audience visibility setting.
BEGIN;
UPDATE public.app_document_records
SET payload = (
        jsonb_set(
            jsonb_set(
                jsonb_set(payload, '{moderationStatus}', '"PENDING"'::jsonb, true),
                '{moderationLeaseUntil}', 'null'::jsonb, true
            ),
            '{moderationAttempts}', '0'::jsonb, true
        ) - 'moderationFailureReason'
    ),
    updated_at = now()
WHERE collection_name = 'posts'
  AND document_id = 'PASTE_POST_ID_HERE'
  AND coalesce(payload->>'moderationStatus', 'APPROVED') = 'APPROVED'
RETURNING
    document_id AS post_id,
    payload->>'moderationStatus' AS moderation_status,
    payload->>'moderationAttempts' AS moderation_attempts;
COMMIT;

-- After the UPDATE returns one row, the scheduler should pick up PENDING within one scan interval.
-- Reload the author's feed after the moderation worker logs its final decision. If the app uses a
-- shared Redis feed cache, allow the configured cache TTL to expire or invalidate feedPages through
-- the app's cache invalidator before checking the UI again.
