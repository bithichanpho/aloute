const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
const before = fs.readFileSync('target/community-discover-template.html', 'utf8');
const after = fs.readFileSync('target/community-joined-template.html', 'utf8');

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  try {
    for (const width of [1280, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 950 } });
      let joined = false, failJoin = true;
      const errors = [], joins = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => {
        window.SockJS = function () {};
        window.Stomp = { over: () => ({ connect() {}, subscribe() {} }) };
      });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://aloute.test') return route.fulfill({ status: 204, body: '' });
        if (url.pathname === '/api/communities/8/join') {
          joins.push(route.request().method());
          if (failJoin) return route.fulfill({ status: 503, contentType: 'application/json',
            body: '{"success":false,"message":"Không thể tham gia lúc này"}' });
          joined = true;
          return route.fulfill({ contentType: 'application/json', body: '{"success":true,"data":{"id":8,"member":true}}' });
        }
        if (url.pathname.startsWith('/api/')) return route.fulfill({ contentType: 'application/json',
          body: '{"success":true,"data":{"count":0}}' });
        if (url.pathname.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript',
          body: url.pathname === '/js/app.js' ? fs.readFileSync('src/main/resources/static/js/app.js') : '' });
        const file = 'src/main/resources/static' + url.pathname;
        if (fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({ contentType:
          url.pathname.endsWith('.css') ? 'text/css' : url.pathname.endsWith('.woff2') ? 'font/woff2' : 'image/png', body: fs.readFileSync(file) });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: joined ? after : before });
      });
      await page.goto('https://aloute.test/communities');
      const mine = page.locator('#myCommunitiesSection'), discover = page.locator('#discoverCommunitiesSection');
      assert.equal(await mine.locator('.community-card').count(), 1);
      assert.equal(await discover.locator('.community-card').count(), 1);
      assert.ok(!(await discover.innerText()).includes('Nhóm đã tham gia'));
      await discover.getByRole('button', { name: 'Tham gia', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('#discoverCommunitiesSection .btn-community-join')?.dataset.busy === '0');
      assert.equal(await discover.locator('.community-card').count(), 1, 'Failed join must keep the discoverable group');
      assert.equal(await mine.locator('.community-card').count(), 1);
      failJoin = false;
      await discover.getByRole('button', { name: 'Tham gia', exact: true }).click();
      await page.waitForFunction(() => document.querySelectorAll('#myCommunitiesSection .community-card').length === 2);
      assert.equal(await discover.locator('.community-card').count(), 0);
      assert.ok((await discover.innerText()).includes('Không còn hội nhóm công khai nào bạn chưa tham gia.'));
      assert.deepEqual(joins, ['POST', 'POST']);
      assert.deepEqual(errors, []);
      await page.screenshot({ path: `target/community-discovery-joined-${width}.png`, animations: 'disabled' });
      console.log(`PASS: ${width}px, joined groups excluded; successful join moves group immediately; failed join retains group`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
