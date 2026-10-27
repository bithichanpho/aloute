const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Real server-rendered feed fragment and application assets; all routes and writes are isolated.
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const fragment = fs.readFileSync('target/username-profile-feed-fragment.html', 'utf8');
  const shell = (body, scripts = '') => `<!doctype html><html lang="vi" data-bs-theme="dark"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/css/main.css"><link rel="stylesheet" href="/css/theme.css"></head><body data-current-user-id="1"><nav class="mobile-topbar" style="display:flex;position:static"><a id="home" href="/feed">Trang chủ</a><a id="mine" href="/@viewer">Trang cá nhân</a></nav><main style="max-width:700px;margin:32px auto">${body}</main><div id="friendsChatDock"></div><script src="/js/app.js"></script><script src="/js/features.js"></script><script src="/js/friends-panel.js"></script>${scripts}</body></html>`;
  try {
    for (const width of [1280, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 850 } }); page.setDefaultTimeout(7000);
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/search/users') return reply(route, [{ id: 26, username: 'caso', fullName: 'Caso', followedByCurrentUser: true }]);
        if (path === '/api/search/posts') return reply(route, [{ id: 'search-post', userId: 26, username: 'caso', content: 'Search card' }]);
        if (path === '/api/follow-requests/cursor') return reply(route, { items: [{ id: '27:1', followerId: 27, followerUsername: 'alice.name', followerFullName: 'Alice' }], hasMore: false });
        if (path.startsWith('/api/')) return reply(route, []);
        if (['/js/app.js', '/js/features.js', '/js/friends-panel.js', '/css/main.css', '/css/theme.css'].includes(path))
          return route.fulfill({ path: 'src/main/resources/static' + path });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path.endsWith('.png') || path.endsWith('.jpg')) return route.fulfill({ path: 'src/main/resources/static/images/avatar_default.png' });
        let content;
        if (path.startsWith('/@')) content = `<h1 id="profileName">${path.slice(2)}</h1>`;
        else if (path === '/search') content = '<input id="searchInput"><div id="searchTabs"><button class="search-tab" data-tab="users">Người dùng</button><button class="search-tab" data-tab="posts">Bài viết</button></div><div id="searchResults"></div>';
        else if (path === '/settings') content = '<div id="featureContent"><div id="followRequestList"></div><button data-action="load-more-requests">Xem thêm</button></div>';
        else content = fragment + '<aside id="friendsPanel"><button data-friend-chat-open data-user-id="26" data-username="caso" data-fullname="Caso">Chat Caso</button></aside>';
        return route.fulfill({ contentType: 'text/html', body: shell(content) });
      });
      await page.goto('http://aloute.test/feed');
      assert.equal(await page.locator('.post__username').getAttribute('href'), '/@caso');
      assert.equal(await page.locator('.repost-card__header a').getAttribute('href'), '/@alice.name');
      assert.equal(await page.locator('.post__reposter').getAttribute('href'), '/@legacy%40example.com');
      for (const [username, id, expected] of [
        ['alice.name-1', 26, '/@alice.name-1'], ['alice@example.com', 26, '/@alice%40example.com'],
        ['123', 26, '/@123'], [null, 26, '/profile/26'], ['deleted_1', 26, '/profile/26'],
        ['javascript:alert(1)', 26, '/profile/26'], ['a/b', 26, '/profile/26'], [null, null, '#']
      ]) assert.equal(await page.evaluate(([name, userId]) => Aloute.profileUrl(name, userId), [username, id]), expected);

      await page.locator('[data-friend-chat-open]').click();
      await page.locator('.friends-chat-window a[title="Xem trang cá nhân"]').waitFor();
      assert.equal(await page.locator('.friends-chat-window a[title="Xem trang cá nhân"]').getAttribute('href'), '/@caso');
      // A new handle route must keep the existing soft-navigation lifecycle and browser history.
      await page.evaluate(() => { window.profileDocumentMarker = 42; });
      await page.locator('#mine').click(); await page.waitForURL('**/@viewer');
      assert.equal(await page.locator('#profileName').innerText(), 'viewer');
      assert.equal(await page.evaluate(() => window.profileDocumentMarker), 42);
      await page.goBack(); await page.waitForURL('**/feed'); await page.locator('.post__username').waitFor();
      // Actual 302 legacy compatibility is covered by UsernameProfileControllerTest.
      await page.goto('http://aloute.test/@caso');
      assert.equal(await page.locator('#profileName').innerText(), 'caso');
      await page.goto('http://aloute.test/search');
      await page.locator('#searchInput').fill('caso'); await page.locator('#searchInput').press('Enter');
      await page.locator('#searchResults .discover-user__main').waitFor();
      assert.equal(await page.locator('#searchResults .discover-user__main').getAttribute('href'), '/@caso');
      await page.locator('[data-tab="posts"]').click(); await page.locator('#searchResults .post__username').waitFor();
      assert.equal(await page.locator('#searchResults .post__username').getAttribute('href'), '/@caso');
      await page.goto('http://aloute.test/settings'); await page.locator('[data-action="load-more-requests"]').click();
      await page.locator('#followRequestList a').waitFor();
      assert.equal(await page.locator('#followRequestList a').getAttribute('href'), '/@alice.name');
      await page.locator('#followRequestList a').click(); await page.waitForURL('**/@alice.name');
      assert.deepEqual(errors, []); console.log(`PASS: feed/quote/repost, helper safety, chat, search, request links, direct URL and soft navigation; width=${width}`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
