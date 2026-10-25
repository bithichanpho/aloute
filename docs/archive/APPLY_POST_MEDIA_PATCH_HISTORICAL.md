> Historical note only: these instructions refer to an older source revision and are not required to use this full merged source. Follow `MERGE_REPORT.md` and `docs/PRODUCTION_RUNBOOK.md` instead.

# Apply the consolidated Eclipse-first backend patch

This delta patch is based only on `aloute_eclipse_post_media_hardened_source.zip` (the immediately previous consolidated source revision), not the original `src.zip` or earlier patch archives. The safest option remains importing the newest full source ZIP into a new Eclipse workspace project.

1. Stop `AlouteApplication` and back up the current source folder, `.env`, Firebase Admin JSON, and local `.secret` content.
2. If applying the delta patch, first confirm the project is exactly the source revision stated above; extract the patch at its project root. Do not apply it on top of the newest full source ZIP, because that would create a mixed revision.
3. Keep the real `.env`, Firebase Admin JSON, `.secret`, `.git`, and `target` outside shared ZIPs. Use `.env.example` only as a template, never as a replacement for the real local values.
4. Configure `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and `CLOUDINARY_API_SECRET` for the actual Cloudinary account. URL ownership checks compare against the configured cloud name, not just the shared `res.cloudinary.com` hostname.
5. For Eclipse direct runs, start local Redis and set `REDIS_URL=redis://localhost:6379` if Redis is bound to loopback without authentication. Keep `REDIS_CACHE_ENABLED=true` and `RATE_LIMIT_REDIS_REQUIRED=true`; rate limiting intentionally fails closed if required Redis is unavailable.
6. To test actual non-post moderation, set `MODERATION_ENABLED=true`, run `moderation/main.py` on `http://localhost:8000`, and set matching `MODERATION_API_TOKEN` values plus a valid `OPENAI_API_KEY`. Do not expose raw/document upload; it is disabled until a dedicated scanner exists.
7. Use test Cloudinary assets/accounts before testing HIDE/delete/retry. When an asset is deleted or queued for deletion, admin APPROVE is intentionally blocked; upload replacement media and create a new post instead.
8. In Eclipse, select JDK 21, run Maven → Update Project, and execute the commands below from PowerShell at the project root:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd verify
.\deploy\scripts\validate-empty-schema.ps1
```

The schema script is for a disposable local database only and must never target Supabase. Docker Compose/Coturn deployment remains a separate team phase. This patch does not claim to provision TURN or RabbitMQ for cross-instance WebSocket fan-out.
