const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const assets = Object.fromEntries(['/js/app.js', '/js/features.js', '/css/main.css', '/css/theme.css']
    .map(path => [path, fs.readFileSync('src/main/resources/static' + path, 'utf8')]));
  const ssr = fs.readFileSync('target/post-audience-fragment.html', 'utf8');
  const labels = { PUBLIC: 'Công khai', FRIENDS: 'Bạn bè', CLOSE_FRIENDS: 'Bạn thân', ONLY_ME: 'Chỉ mình tôi' };
  const html = body => `<!doctype html><html data-bs-theme="dark"><head><meta charset="utf-8"><link rel="stylesheet" href="/css/main.css"><link rel="stylesheet" href="/css/theme.css"></head><body data-current-user-id="1">${body}<script src="/js/app.js"></script><script src="/js/features.js"></script></body></html>`;
  const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  try {
    for (const width of [1280, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 800 } });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (assets[path]) return route.fulfill({ contentType: path.endsWith('.js') ? 'application/javascript' : 'text/css', body: assets[path] });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path === '/api/search/posts') return reply(route, Object.keys(labels).map((visibility, i) => ({
          id: 'post-' + i, userId: 1, username: 'Caso', content: labels[visibility], timeAgo: 'Vừa xong', visibility,
          moderationStatus: i === 0 ? 'FLAGGED' : 'APPROVED', moderationRetryAvailable: i === 0
        })));
        if (path.startsWith('/api/')) return reply(route, []);
        if (path.endsWith('.png')) return route.fulfill({ contentType: 'image/png', body: fs.readFileSync('src/main/resources/static/images/avatar_default.png') });
        return route.fulfill({ contentType: 'text/html', body: html(`<main style="max-width:700px;margin:20px auto;padding:16px"><div id="serverIcons">${ssr}</div><input id="searchInput" aria-label="Tìm kiếm"><div id="searchTabs"><button class="search-tab" data-tab="posts">Bài viết</button></div><div id="searchResults"></div></main>`) });
      });
      await page.goto('http://aloute.test/search');
      await page.locator('#searchInput').fill('Caso');
      await page.locator('[data-tab="posts"]').click();
      await page.locator('#searchResults .post__audience').first().waitFor();
      for (const [audience, label] of Object.entries(labels)) {
        for (const parent of ['#serverIcons', '#searchResults']) {
          const icon = page.locator(`${parent} [data-post-audience="${audience}"]`);
          assert.equal(await icon.getAttribute('aria-label'), label);
          assert.equal(await icon.getAttribute('title'), label);
          assert.equal(await icon.locator('svg').count(), 1);
          const box = await icon.boundingBox(); assert.ok(box.width >= 14 && box.height >= 14);
        }
      }
      assert.equal(await page.locator('[data-moderation-retry]').count(), 1);
      assert.match(await page.locator('.post__review').innerText(), /mất kết nối/);
      assert.deepEqual(errors, []);
      await page.screenshot({ path: `target/post-audience-${width}.png`, fullPage: true });
      await page.close(); console.log(`PASS: audience icons, owner status, width=${width}`);
    }

    const page = await browser.newPage();
    let attempts = 0, done = false;
    await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf', url: 'http://aloute.test' }]);
    await page.route('http://aloute.test/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (assets[path]) return route.fulfill({ contentType: path.endsWith('.js') ? 'application/javascript' : 'text/css', body: assets[path] });
      if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
      if (path === '/api/posts/post/moderation-retry') {
        assert.equal(route.request().method(), 'POST');
        assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf');
        attempts++; await new Promise(resolve => setTimeout(resolve, 100));
        if (attempts === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại sau"}' });
        done = true; return reply(route, null);
      }
      if (path.startsWith('/api/')) return reply(route, []);
      const button = '<button data-moderation-retry data-review-post-id="post">Thử duyệt lại</button>';
      return route.fulfill({ contentType: 'text/html', body: html(done ? '<p>Đang kiểm duyệt</p>' : button + button) });
    });
    await page.goto('http://aloute.test/profile/me');
    await page.evaluate(() => document.querySelectorAll('[data-moderation-retry]').forEach(button => button.click()));
    await page.waitForFunction(() => [...document.querySelectorAll('[data-moderation-retry]')].every(button => !button.disabled));
    assert.equal(attempts, 1);
    await Promise.all([page.waitForEvent('load'), page.locator('[data-moderation-retry]').first().click()]);
    assert.equal(attempts, 2); assert.match(await page.locator('body').innerText(), /Đang kiểm duyệt/);
    await page.close(); console.log('PASS: retry duplicate click prevention, CSRF, error recovery, successful reload');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
