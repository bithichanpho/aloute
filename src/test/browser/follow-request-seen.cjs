const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const inbox = fs.readFileSync('target/follow-requests-template.html', 'utf8');
  try {
    for (const width of [1280, 390]) for (const delayed of [false, true]) {
      const page = await browser.newPage({ viewport: { width, height: 850 } });
      const errors = [], mutations = [];
      let unread = 1, reads = 0, held = false, release;
      const firstReply = new Promise(resolve => { release = resolve; });
      await page.addInitScript(() => {
        window.__frames = {};
        window.__resurrected = [];
        window.SockJS = function () {};
        window.Stomp = { over: () => ({ connect: (_, ready) => ready(), subscribe: (name, callback) => {
          window.__frames[name] = callback;
        }, send() {} }) };
        new MutationObserver(() => {
          const badge = document.getElementById('followRequestBadge');
          if (!window.__allowNew && document.querySelector('.follow-requests-page') && badge && Number(badge.textContent) > 0 && badge.style.display !== 'none') {
            window.__resurrected.push(badge.textContent);
          }
        }).observe(document, { subtree: true, childList: true, attributes: true, characterData: true });
      });
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url()), path = url.pathname;
        if (url.origin !== 'http://aloute.test') return route.fulfill({ status: 204, body: '' });
        const reply = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
        if (path === '/api/follow-requests/unread-count') {
          const captured = unread; reads++;
          if (delayed && reads === 1) { held = true; await firstReply; }
          return reply({ count: captured });
        }
        if (path === '/api/follow-requests/cursor') return reply({ items: [
          { id: '6:1', followerId: 6, followerUsername: 'minhan', followerFullName: 'Minh An' },
          { id: '7:1', followerId: 7, followerUsername: 'bob', followerFullName: 'Bob' }
        ], hasMore: false });
        if (path.startsWith('/api/')) {
          if (request.method() !== 'GET') mutations.push(path);
          return reply({ count: 0 });
        }
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body:
          ['/js/app.js', '/js/features.js'].includes(path) ? fs.readFileSync('src/main/resources/static' + path) : '' });
        const asset = 'src/main/resources/static' + path;
        if (fs.existsSync(asset) && fs.statSync(asset).isFile()) return route.fulfill({ contentType:
          path.endsWith('.css') ? 'text/css' : path.endsWith('.woff2') ? 'font/woff2' : 'image/png', body: fs.readFileSync(asset) });
        if (path === '/follow-requests') {
          // HTTP fixture represents the authenticated controller acknowledging existing requests.
          unread = 0;
          return route.fulfill({ contentType: 'text/html; charset=utf-8', body: inbox });
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<!doctype html><html><body data-current-user-id="1">
          <nav class="sidebar"><a href="/follow-requests">Yêu cầu theo dõi <span id="followRequestBadge">1</span></a></nav>
          <script src="/js/app.js"></script><script src="/js/features.js"></script></body></html>` });
      });
      const initialCountResponse = delayed ? null : page.waitForResponse(response => response.url().includes('/follow-requests/unread-count'));
      await page.goto('http://aloute.test/feed');
      if (delayed) await page.waitForFunction(() => window.__frames['/user/queue/follow-requests']);
      else await initialCountResponse;
      await page.getByRole('link', { name: /Yêu cầu theo dõi/ }).click();
      await page.locator('.follow-requests-page').waitFor();
      if (delayed) { assert.equal(held, true); release(); }
      await page.waitForFunction(() => document.getElementById('followRequestBadge').style.display === 'none');
      await page.waitForFunction(() => document.querySelectorAll('#followRequestList [data-follow-request-actions]').length === 1);
      // Let any stale initial poll resolve and the forced refresh complete.
      await page.waitForFunction(() => !document.getElementById('pageProgress')?.classList.contains('is-active'));
      await page.waitForTimeout(150);
      assert.ok(reads >= 2);
      assert.deepEqual(await page.evaluate(() => window.__resurrected), []);
      await page.reload();
      await page.waitForFunction(() => !!window.__frames['/user/queue/follow-requests']);
      assert.equal(await page.locator('#followRequestBadge').isVisible(), false);
      assert.equal(await page.locator('#followRequestList [data-follow-request-actions]').count(), 1);

      unread = 1;
      await page.evaluate(() => {
        window.__allowNew = true;
        window.__frames['/user/queue/follow-requests']({ body: JSON.stringify({
          eventType: 'REQUEST_CREATED', followerId: 7, targetId: 1, status: 'PENDING'
        }) });
      });
      await page.waitForFunction(() => document.getElementById('followRequestBadge').style.display !== 'none' && document.getElementById('followRequestBadge').textContent === '1');
      await page.waitForFunction(() => document.querySelectorAll('#followRequestList [data-follow-request-actions]').length === 2);

      unread = 0;
      await page.evaluate(() => window.__frames['/user/queue/follow-requests']({ body: JSON.stringify({ eventType: 'REQUESTS_SEEN', targetId: 1 }) }));
      await page.waitForFunction(() => document.getElementById('followRequestBadge').style.display === 'none');
      assert.equal(await page.locator('#followRequestList [data-follow-request-actions]').count(), 2);
      assert.equal(await page.locator('.aloute-toast').count(), 0);
      assert.deepEqual(mutations, []);
      assert.deepEqual(errors, []);
      console.log(`PASS: ${width}/${delayed ? 'delayed-poll' : 'normal'} visit clears reminder, reload keeps pending, new request alerts, other-tab seen clears without popup`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
