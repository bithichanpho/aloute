-- Social-owned, read-only projection for set-based cross-module composition.
-- Neither Posts nor Feed may write friends or depend on its private entity/repository.
-- No materialized copy, new index, backfill or data mutation. Uses existing follow uniqueness index.
CREATE VIEW aloute_read_social_following_authors (viewer_id, value) AS
SELECT follower_id, followed_id::text FROM friends;
COMMENT ON VIEW aloute_read_social_following_authors IS
    'Social public read API v1: current follow edges only; consumers must still apply post visibility/authorization.';
