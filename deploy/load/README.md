# ALOUTE load test

Run against a non-production environment with a valid access cookie:

```powershell
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 `
  -e ACCESS_COOKIE="accessToken=..." -e PROFILE_ID=1 `
  -v "${PWD}/deploy/load:/scripts" grafana/k6 run /scripts/k6-smoke.js
```

The test covers cursor feed, profile and notification cursor. Thresholds fail the run when errors
exceed 1% or p95 latency regresses.

Port note: the merged `application.yml` defaults to `8081` for direct local runs. Docker Compose explicitly sets `SERVER_PORT=8080` and exposes the application through the local reverse proxy on port `8080`. Set `BASE_URL` to the port of the environment actually being tested. Use a test account and never a production access cookie.
