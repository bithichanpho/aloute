# ALOUTE load test

Run against a non-production environment with a valid access cookie:

```powershell
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 `
  -e ACCESS_COOKIE="accessToken=..." -e PROFILE_ID=1 `
  -v "${PWD}/deploy/load:/scripts" grafana/k6 run /scripts/k6-smoke.js
```

The test covers cursor feed, profile and notification cursor. Thresholds fail the run when errors
exceed 1% or p95 latency regresses.
