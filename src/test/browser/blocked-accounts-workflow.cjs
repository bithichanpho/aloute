const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Real Thymeleaf output and application assets; all API writes stay inside this fixture.
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const template = fs.readFileSync('target/blocked-accounts-template.html', 'utf8')
    .replace(/<script\b[^>]*src="([^"]+)"[^>]*>[\s\S]*?<\/script>/g,
      (tag, src) => src.startsWith('/js/app.js') || src.startsWith('/js/features.js') ? tag : '');
  const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  try {
    for (const width of [1280, 390]) for (const theme of ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width, height: 850 } });
      page.setDefaultTimeout(7000);
      const errors = []; page.on('pageerror', error => errors.push(error.stack || error.message));
      let deletions = 0, cursorReads = 0;
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf', url: 'http://aloute.test' }]);
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/blocks/cursor') {
          cursorReads++;
          return reply(route, { items: [
            { targetId: 8, targetUsername: 'minhan', targetFullName: 'Minh An', targetAvatarUrl: '/missing-avatar.png' },
            { targetId: 6, targetUsername: 'duplicate' }
          ], nextCursor: '', hasMore: false });
        }
        if (/^\/api\/blocks\/\d+$/.test(path)) {
          assert.equal(route.request().method(), 'DELETE');
          assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf');
          deletions++;
          if (deletions === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại sau"}' });
          return reply(route, null);
        }
        if (path === '/api/account/2fa') return reply(route, { enabled: false });
        if (path.startsWith('/api/')) return reply(route, []);
        if (path === '/missing-avatar.png') return route.fulfill({ status: 404, body: '' });
        // Realtime vendor clients are outside this fixture; do not open STOMP/SockJS connections.
        if (path.endsWith('.js') && path !== '/js/app.js' && path !== '/js/features.js')
          return route.fulfill({ contentType: 'application/javascript', body: '' });
        const file = 'src/main/resources/static' + path;
        if ((path.startsWith('/css/') || path.startsWith('/vendor/') || path.startsWith('/images/') || path === '/js/app.js' || path === '/js/features.js') && fs.existsSync(file))
          return route.fulfill({ path: file });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path.endsWith('.png')) return route.fulfill({ path: 'src/main/resources/static/images/avatar_default.png' });
        return route.fulfill({ contentType: 'text/html', body: template.replace('data-bs-theme="dark"', `data-bs-theme="${theme}"`) });
      });
      await page.goto('http://aloute.test/settings#settings-blocked');
      assert.equal(await page.locator('html').getAttribute('data-bs-theme'), theme);
      const list = page.locator('#blockedAccountList');
      const row = id => list.locator(`[data-block-id="${id}"]`);
      await row(6).waitFor({ state: 'visible' });
      assert.equal(await row(6).locator('strong').innerText(), 'Alice <script>bad()</script>');
      assert.equal(await row(6).locator('small').innerText(), '@alice');
      assert.equal(await row(6).locator('a').getAttribute('href'), '/profile/6');
      assert.equal(await row(7).locator('strong').innerText(), 'Tài khoản không còn khả dụng');
      assert.equal(await row(7).locator('img').getAttribute('src'), '/images/avatar_default.png');
      assert.equal(await page.evaluate(() => typeof window.bad), 'undefined');
      await row(6).locator('strong').click();
      assert.match(await page.locator('body').innerText(), /Bạn cần bỏ chặn để xem trang cá nhân này/);
      assert.equal(new URL(page.url()).pathname, '/settings'); assert.equal(deletions, 0);

      // Load more uses the same delegated handler and must deduplicate existing accounts.
      await list.evaluate(element => {
        const button = document.createElement('button'); button.type = 'button';
        button.dataset.action = 'load-more-blocks'; button.textContent = 'Xem thêm';
        element.after(button);
      });
      await page.locator('[data-action="load-more-blocks"]').click();
      await row(8).waitFor();
      assert.equal(cursorReads, 1); assert.equal(await list.locator('[data-block-id]').count(), 3);
      assert.equal(await row(8).locator('strong').innerText(), 'Minh An');
      assert.equal(await row(8).locator('small').innerText(), '@minhan');
      await page.waitForFunction(() => document.querySelector('[data-block-id="8"] img').getAttribute('src') === '/images/avatar_default.png');
      assert.equal(await page.locator('[data-action="load-more-blocks"]').count(), 0);
      const avatarBox = await row(6).locator('img').boundingBox();
      assert.equal(avatarBox.width, 42); assert.equal(avatarBox.height, 42);
      for (const id of [6, 7, 8]) {
        const bounds = await row(id).boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width + 1);
      }
      await page.screenshot({ path: `target/blocked-accounts-${width}-${theme}.png`, fullPage: true });

      await row(6).locator('button').click();
      await page.waitForFunction(() => !document.querySelector('[data-block-id="6"] button').disabled);
      assert.equal(await row(6).count(), 1); assert.equal(deletions, 1);
      for (const id of [6, 7, 8]) {
        await row(id).locator('button').click();
        await row(id).waitFor({ state: 'detached' });
      }
      assert.equal(deletions, 4);
      assert.equal(await list.locator('.feature-empty').innerText(), 'Bạn chưa chặn tài khoản nào.');
      assert.deepEqual(errors, []);
      await page.close(); console.log(`PASS: blocked identity, cursor/dedupe, avatar fallback, block guard, CSRF, retry and empty state; width=${width}, theme=${theme}`);
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
