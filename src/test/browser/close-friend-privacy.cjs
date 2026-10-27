const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const root = path.resolve('src/main/resources/static');
  const composer = fs.readFileSync('target/privacy-compose-template.html', 'utf8');
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  async function asset(route) {
    const url = new URL(route.request().url());
    if (url.hostname !== 'aloute.test') { await route.abort(); return true; }
    const file = path.resolve(root, '.' + url.pathname);
    if (file.startsWith(root + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile()) {
      const type = { '.js': 'application/javascript', '.css': 'text/css', '.png': 'image/png', '.woff2': 'font/woff2' };
      await route.fulfill({ contentType: type[path.extname(file)] || 'application/octet-stream', body: fs.readFileSync(file) });
      return true;
    }
    return false;
  }
  try {
    for (const width of [1280, 390]) for (const initial of ['NONE', 'PENDING_RECEIVED', 'ACCEPTED']) {
      const page = await browser.newPage({ viewport: { width, height: 850 } });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => { window.AlouteUI = { confirm: async () => true }; });
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'privacy-csrf', url: 'http://aloute.test' }]);
      let state = initial, mutations = 0, navigations = 0;
      await page.route('**/*', async route => {
        if (await asset(route)) return;
        const request = route.request(), url = new URL(request.url());
        if (url.pathname.startsWith('/api/close-friends/6')) {
          mutations++;
          assert.equal(request.headers()['x-xsrf-token'], 'privacy-csrf');
          assert.equal(request.method(), initial === 'ACCEPTED' ? 'DELETE' : 'POST');
          assert.equal(url.pathname, '/api/close-friends/6' + (initial === 'NONE' ? '/request' : initial === 'PENDING_RECEIVED' ? '/accept' : ''));
          await new Promise(resolve => setTimeout(resolve, 100));
          state = initial === 'NONE' ? 'PENDING_SENT' : initial === 'PENDING_RECEIVED' ? 'ACCEPTED' : 'NONE';
          return reply(route, state);
        }
        if (url.pathname.startsWith('/api/')) return reply(route, 0);
        if (!request.isNavigationRequest()) return route.fulfill({ status: 404, body: '' });
        navigations++;
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body:
          `<body data-current-user-id="1"><div class="profile-cover"><button class="btn-close-friend" data-user-id="6" data-status="${state}">Bạn thân</button>${state === 'ACCEPTED' ? '<p>CLOSE_SECRET</p>' : ''}</div><script src="/js/app.js"></script></body>` });
      });
      await page.goto('http://aloute.test/profile/6');
      const reloaded = page.waitForEvent('load');
      await page.evaluate(() => { const button = document.querySelector('.btn-close-friend'); button.click(); button.click(); });
      await reloaded;
      assert.equal(mutations, 1); assert.equal(navigations, 2);
      assert.equal((await page.locator('body').textContent()).includes('CLOSE_SECRET'), state === 'ACCEPTED');
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ scenario: 'close-friend-decision-reloads-content', initial, width, result: 'PASS' }));
      await page.close();
    }
    for (const width of [1280, 390]) for (const audience of ['PUBLIC', 'FRIENDS', 'CLOSE_FRIENDS', 'ONLY_ME']) {
      const page = await browser.newPage({ viewport: { width, height: 850 } });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'privacy-csrf', url: 'http://aloute.test' }]);
      let posts = 0;
      await page.route('**/*', async route => {
        if (await asset(route)) return;
        const request = route.request(), url = new URL(request.url());
        if (url.pathname === '/api/posts' && request.method() === 'POST') {
          posts++; assert.equal(request.headers()['x-xsrf-token'], 'privacy-csrf');
          assert.equal(request.postDataJSON().visibility, audience);
          return reply(route, { id: 'fixture-post', moderationStatus: 'PENDING' });
        }
        if (url.pathname.startsWith('/api/')) return reply(route, 0);
        if (!request.isNavigationRequest()) return route.fulfill({ status: 404, body: '' });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: url.pathname === '/compose' ? composer : '<p>Feed fixture</p>' });
      });
      await page.goto('http://aloute.test/compose');
      await page.locator('[data-audience-open]').click();
      assert.equal(await page.locator('#postAudienceMenu [role="option"]').count(), 4);
      await page.locator('[data-audience-value="' + audience + '"]').click();
      await page.locator('#composeContent').fill('Bài kiểm tra quyền xem trong fixture.');
      await page.locator('#composeSubmit').click();
      await page.waitForURL('**/feed*');
      assert.equal(posts, 1); assert.deepEqual(errors, []);
      console.log(JSON.stringify({ scenario: 'composer-audience-contract', audience, width, result: 'PASS' }));
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
