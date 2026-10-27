const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const template = fs.readFileSync('target/search-template.html', 'utf8');
  try {
    for (const width of [1280, 390]) for (const theme of ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width, height: 950 } });
      const errors = [], calls = [];
      let failFeed = false;
      page.on('pageerror', error => errors.push(error.message));
      const post = (id, content) => ({ id, userId: 6, username: 'minhan', content,
        moderationStatus: 'APPROVED', visibility: 'PUBLIC', timeAgo: '1 giờ trước', reactionCounts: {} });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url()), path = url.pathname;
        if (url.origin !== 'http://aloute.test') return route.fulfill({ status: 204, body: '' });
        const reply = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
        if (path.startsWith('/api/')) {
          calls.push(url.pathname + url.search);
          if (path === '/api/search/suggestions') return reply([{ id: 6, username: 'suggestion', fullName: 'Bạn gợi ý' }]);
          if (path === '/api/search/trending') return reply([{ topic: '#ALOUTE', query: '#ALOUTE', meta: '2 bài viết' }]);
          if (path === '/api/posts/feed/cursor') {
            if (failFeed) return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại sau"}' });
            const next = url.searchParams.get('cursor');
            if (next) assert.equal(next, 'opaque+/cursor=');
            return reply({ items: [post(next ? 'page-two' : 'page-one', next ? 'Bài trang sau' : 'Bài gợi ý')],
              hasMore: !next, nextCursor: next ? null : 'opaque+/cursor=' });
          }
          if (path === '/api/search/posts') {
            const q = url.searchParams.get('q');
            assert.ok(q, 'Empty Posts tab must use the cursor feed');
            return reply(q === 'missing' ? [] : [post('match', 'Kết quả ' + q)]);
          }
          if (path === '/api/search/users') return reply([{ id: 9, username: 'matched', fullName: 'Người ' + url.searchParams.get('q') }]);
          return reply({ count: 0 });
        }
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body:
          path === '/js/app.js' ? fs.readFileSync('src/main/resources/static' + path) : '' });
        const asset = 'src/main/resources/static' + path;
        if (fs.existsSync(asset) && fs.statSync(asset).isFile()) return route.fulfill({ contentType:
          path.endsWith('.css') ? 'text/css' : path.endsWith('.woff2') ? 'font/woff2' : 'image/png', body: fs.readFileSync(asset) });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: template.replace('data-bs-theme="dark"', `data-bs-theme="${theme}"`) });
      });
      await page.goto('http://aloute.test/search');
      const input = page.locator('#searchInput'), clear = page.locator('#searchClear');
      const results = page.locator('#searchResults'), discover = page.locator('#searchDiscover');
      const posts = page.locator('.search-tab[data-tab="posts"]'), users = page.locator('.search-tab[data-tab="users"]');
      await page.locator('#suggestionList .discover-user').waitFor();
      assert.equal(await clear.isVisible(), false);
      assert.equal(await results.isVisible(), false);
      assert.equal(await posts.isVisible(), true);
      await posts.click();
      await page.locator('.post[data-post-id="page-one"]').waitFor();
      assert.equal(await discover.isVisible(), false);
      await page.locator('.search-load-more').click();
      await page.locator('.post[data-post-id="page-two"]').waitFor();
      assert.equal(await results.locator('.post').count(), 2);
      assert.equal(await page.locator('.search-load-more').count(), 0);
      await input.fill('#ALOUTE'); await input.press('Enter');
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Kết quả #ALOUTE'));
      assert.ok(calls.some(url => url.includes('q=%23ALOUTE')));
      assert.equal(await discover.isVisible(), false);
      await page.screenshot({ path: `target/search-${width}-${theme}.png` });
      await input.fill('ALOUTE'); await input.press('Enter');
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Kết quả ALOUTE'));
      await users.click();
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Người ALOUTE'));
      await clear.click();
      assert.equal(await discover.isVisible(), true);
      assert.equal(await results.isVisible(), false);
      await page.locator('[data-search-query="#ALOUTE"]').click();
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Kết quả #ALOUTE'));
      assert.equal(await posts.getAttribute('class').then(value => value.includes('is-active')), true);
      // Clearing before the debounce fires must not restore the old search afterwards.
      const requestsBefore = calls.length;
      await input.fill('cancelled'); await clear.click();
      await page.locator('.post[data-post-id="page-one"]').waitFor();
      await page.waitForTimeout(400);
      assert.equal(calls.slice(requestsBefore).some(url => url.includes('q=cancelled')), false);
      await users.click();
      assert.equal(await discover.isVisible(), true);
      // A tab switch during debounce uses the currently visible text, not a previous keyword.
      await input.fill('fresh'); await posts.click();
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Kết quả fresh'));
      await input.fill('missing'); await input.press('Enter');
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Chưa có bài viết nào khớp'));
      assert.equal(await discover.isVisible(), false);
      failFeed = true; await clear.click();
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Không tải được kết quả'));
      failFeed = false; await users.click(); await posts.click();
      await page.locator('.post[data-post-id="page-one"]').waitFor();
      assert.deepEqual(errors, []);
      await page.close();
      console.log(JSON.stringify({ width, theme, passed: true, requests: calls.length }));
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
