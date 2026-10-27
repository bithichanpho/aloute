const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const script = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
  const html = activity => `<!doctype html><html><body data-current-user-id="1"><nav class="sidebar"><a href="/feed">Trang chủ</a><a href="/activity">Thông báo</a><span id="notifUnreadBadge">5</span><span id="chatUnreadBadge">2</span></nav><span data-badge-mirror="notifUnreadBadge">5</span>${activity ? '<section><button id="markAllReadBtn">Đã đọc</button><div class="notif-item notif-item--unread"></div></section>' : '<main>Trang chủ</main>'}<script src="/js/app.js"></script></body></html>`;
  try {
    for (const scenario of ['slow-entry', 'leave-during-write', 'history-return']) {
      const page = await browser.newPage(); page.setDefaultTimeout(5000);
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let unread = 5, writes = 0, activityRoute, writeRoute;
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf', url: 'http://aloute.test' }]);
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        const reply = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
        if (path === '/js/app.js') return route.fulfill({ contentType: 'application/javascript', body: script });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path === '/api/notifications/read-all') {
          writes++;
          assert.equal(route.request().method(), 'POST');
          assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf');
          if (scenario === 'leave-during-write') { writeRoute = route; return; }
          unread = 0; return reply(null);
        }
        if (path === '/api/notifications/unread-count') return reply({ count: unread });
        if (path === '/api/chat/unread-count') return reply({ count: 2 });
        if (path.startsWith('/api/')) return reply([]);
        if (path === '/activity' && scenario === 'slow-entry') { activityRoute = route; return; }
        // A page response can contain a count captured before the read-all commit.
        return route.fulfill({ contentType: 'text/html', body: html(path === '/activity') });
      });
      await page.goto('http://aloute.test/feed');
      await page.waitForFunction(() => document.querySelector('#notifUnreadBadge').textContent === '5');
      if (scenario === 'history-return') {
        unread = 0;
        await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
      } else {
        await page.locator('a[href="/activity"]').click();
        await page.waitForFunction(() => document.querySelector('#notifUnreadBadge').style.display === 'none');
        if (scenario === 'slow-entry') {
          assert.ok(activityRoute);
          await activityRoute.fulfill({ contentType: 'text/html', body: html(true) });
        }
        await page.waitForURL('**/activity');
        await page.locator('#markAllReadBtn').waitFor();
        await page.waitForFunction(() => !!document.querySelector('a[href="/feed"]')?.dataset.softNavBound);
        await page.locator('a[href="/feed"]').click();
        await page.waitForURL('**/feed');
        if (scenario === 'leave-during-write') {
          assert.ok(writeRoute);
          unread = 0;
          await writeRoute.fulfill({ contentType: 'application/json', body: '{"success":true,"data":null}' });
        }
      }
      await page.waitForFunction(() => document.querySelector('#notifUnreadBadge').style.display === 'none');
      assert.equal(await page.locator('[data-badge-mirror="notifUnreadBadge"]').getAttribute('style'), 'display: none;');
      await page.evaluate(() => window.Aloute.refreshNavBadges(true));
      assert.equal(await page.locator('#notifUnreadBadge').innerText(), '0');
      assert.equal(await page.locator('#chatUnreadBadge').innerText(), '2');
      assert.equal(writes, scenario === 'history-return' ? 0 : 1);
      assert.deepEqual(errors, []);
      console.log(`PASS: ${scenario}`); await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
