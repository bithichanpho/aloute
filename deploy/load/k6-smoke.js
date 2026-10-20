import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    social_reads: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || '30s', target: Number(__ENV.VUS || 50) },
        { duration: __ENV.HOLD || '2m', target: Number(__ENV.VUS || 50) },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:feed}': ['p(95)<800'],
    'http_req_duration{endpoint:profile}': ['p(95)<600'],
    'http_req_duration{endpoint:notifications}': ['p(95)<500'],
  },
};

const base = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const params = { headers: { Cookie: __ENV.ACCESS_COOKIE || '' } };
const profileId = __ENV.PROFILE_ID || '1';

export default function () {
  const feed = http.get(`${base}/api/posts/feed/cursor?size=20`, {
    ...params, tags: { endpoint: 'feed' },
  });
  check(feed, { 'feed succeeds': (r) => r.status === 200 });
  const profile = http.get(`${base}/api/users/${profileId}`, {
    ...params, tags: { endpoint: 'profile' },
  });
  check(profile, { 'profile succeeds': (r) => r.status === 200 });
  const notifications = http.get(`${base}/api/notifications/cursor?size=30`, {
    ...params, tags: { endpoint: 'notifications' },
  });
  check(notifications, { 'notifications succeed': (r) => r.status === 200 });
  sleep(1);
}
