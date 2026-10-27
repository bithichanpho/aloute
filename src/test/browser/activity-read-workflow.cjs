const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Real app.js and isolated HTTP fixtures: never changes a user's notifications.
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const script = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
  const html = (activity, count) => `<!doctype html><html><body data-current-user-id="1">
    <nav class="sidebar"><a href="/feed">Trang chủ</a><a href="/activity">Thông báo</a><span id="notifUnreadBadge" style="${count ? '' : 'display:none'}">${count}</span><span id="chatUnreadBadge">3</span></nav>
    <span data-badge-mirror="notifUnreadBadge" style="${count ? '' : 'display:none'}">${count}</span>
    ${activity ? '<section><button id="markAllReadBtn">Đánh dấu tất cả đã đọc</button><div class="notif-item notif-item--unread" data-notification-id="10"><button data-follow-decision="accept">Chấp nhận</button><button data-follow-decision="reject">Từ chối</button></div></section>' : '<main>Trang chủ</main>'}
    <script src="/js/app.js"></script></body></html>`;
  try {
    for (const scenario of ['direct', 'soft', 'stale-count', 'new-notification', 'retry']) {
      const page = await browser.newPage({ viewport: { width: scenario === 'soft' ? 390 : 1280, height: 800 } });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let unread = 5, writes = 0, reads = 0, rejectFirst = scenario === 'retry';
      const oldCountRequests = [];
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'activity-fixture-csrf', url: 'http://aloute.test' }]);
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        const reply = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
        if (path === '/js/app.js') return route.fulfill({ contentType: 'application/javascript', body: script });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path === '/api/notifications/read-all') {
          assert.equal(route.request().method(), 'POST');
          assert.equal(route.request().headers()['x-xsrf-token'], 'activity-fixture-csrf');
          writes++;
          await new Promise(resolve => setTimeout(resolve, 80));
          if (rejectFirst) {
            rejectFirst = false;
            return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Không thể đánh dấu đã đọc"}' });
          }
          unread = scenario === 'new-notification' ? 1 : 0;
          return reply(null);
        }
        if (path === '/api/notifications/unread-count') {
          reads++;
          if (scenario === 'stale-count' && reads === 1) {
            oldCountRequests.push(route); return;
          }
          return reply({ count: unread });
        }
        if (path === '/api/chat/unread-count') return reply({ count: 3 });
        if (path.startsWith('/api/')) return reply([]);
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html(path === '/activity', unread) });
      });
      await page.goto('http://aloute.test/' + (scenario === 'soft' ? 'feed' : 'activity'));
      if (scenario === 'soft') {
        await page.waitForTimeout(100);
        assert.equal(writes, 0, 'Other pages must not acknowledge notifications');
        await page.locator('a[href="/activity"]').click();
        await page.waitForURL('**/activity');
      }
      await page.waitForFunction(() => document.querySelector('#markAllReadBtn')?.disabled);
      if (scenario === 'retry') {
        await page.waitForFunction(() => !document.querySelector('#markAllReadBtn').disabled);
        assert.equal(await page.locator('#notifUnreadBadge').innerText(), '5');
        assert.equal(await page.locator('.notif-item--unread').count(), 1);
        await page.locator('#markAllReadBtn').click();
      }
      if (scenario === 'stale-count') {
        await page.waitForFunction(() => document.querySelector('#notifUnreadBadge').style.display === 'none');
        assert.equal(oldCountRequests.length, 1);
        await oldCountRequests[0].fulfill({ contentType: 'application/json', body: '{"success":true,"data":{"count":5}}' });
      }
      await page.waitForFunction(() => !document.querySelector('#markAllReadBtn').disabled);
      await page.waitForFunction(expected => document.querySelector('#notifUnreadBadge').textContent === expected,
        scenario === 'new-notification' ? '1' : '0');
      assert.equal(await page.locator('[data-badge-mirror="notifUnreadBadge"]').innerText(), scenario === 'new-notification' ? '1' : '0');
      assert.equal(await page.locator('.notif-item--unread').count(), 0);
      assert.equal(await page.locator('[data-follow-decision]').count(), 2, 'Reading must not accept/reject follow requests');
      assert.equal(await page.locator('#chatUnreadBadge').innerText(), '3');
      assert.equal(writes, scenario === 'retry' ? 2 : 1);
      if (scenario === 'direct') {
        await page.reload();
        await page.waitForFunction(() => !document.querySelector('#markAllReadBtn').disabled);
        assert.equal(await page.locator('#notifUnreadBadge').innerText(), '0');
      }
      assert.deepEqual(errors, []);
      console.log(`PASS: ${scenario}`); await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
