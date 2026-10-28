const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const before = process.argv[2] === 'before';
  const source = before ? require('node:child_process').execFileSync('git', ['show', 'HEAD:src/main/resources/static/js/app.js'])
    : fs.readFileSync('src/main/resources/static/js/app.js');
  const template = fs.readFileSync('target/profile-media-template.html', 'utf8').replace('</head>',
    '<style>[data-load-posts]:not([data-profile-reposts-loader]){margin-top:3600px}</style></head>');
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const fragment = (id, quoted = false) => `<div class="post${quoted ? ' post--repost' : ''}" data-post-id="${id}">
    <a href="/people/author.name" class="post__username">author.name</a><img class="post__image" src="/images/avatar_default.png">
    <button class="action--like"><span>2</span></button><button class="action--reply"><span>1</span></button></div>`;
  try {
    for (const width of before ? [1280] : [1280, 390]) for (const theme of before ? ['dark'] : ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width, height: 950 } });
      const errors = [], calls = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/*', async route => {
        const url = new URL(route.request().url()), path = url.pathname;
        if (url.origin !== 'http://aloute.test') return route.fulfill({ status: 204, body: '' });
        if (path.endsWith('/reposts/fragment')) {
          calls.push(url.href);
          return route.fulfill({ contentType: 'text/html', headers: { 'X-Has-More': 'false', 'X-Next-Cursor': '' }, body: fragment('reposted-photo') });
        }
        if (path === '/api/posts/user/1/fragment') {
          calls.push(url.href);
          return route.fulfill({ contentType: 'text/html', headers: { 'X-Has-More': 'false', 'X-Next-Cursor': '' },
            body: fragment('own-next') + fragment('quote-next', true) });
        }
        if (path.startsWith('/api/')) return route.fulfill({ contentType: 'application/json', body: '{"success":true,"data":{"count":0}}' });
        if (path === '/js/app.js') return route.fulfill({ contentType: 'application/javascript', body: source });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        const asset = 'src/main/resources/static' + path;
        if (fs.existsSync(asset) && fs.statSync(asset).isFile()) return route.fulfill({ path: asset });
        if (path.startsWith('/fixture/')) return route.fulfill({ path: 'src/main/resources/static/images/avatar_default.png' });
        return route.fulfill({ contentType: 'text/html', body: template.replace('data-bs-theme="dark"', `data-bs-theme="${theme}"`) });
      });
      await page.goto('http://aloute.test/people/tester');
      const tiles = page.locator('#profileGrid .grid-tile');
      assert.equal(await tiles.count(), 1);
      await page.locator('[data-profile-view="reposts"]').click();
      await page.locator('#profileRepostsWrap .post[data-post-id="reposted-photo"]').waitFor();
      if (before) {
        assert.equal(await tiles.count(), 2, 'Reproduce the original bug: repost page contaminates grid');
        console.log('CONFIRMED before: opening Reposts adds a foreign post to the profile grid');
        await page.close(); continue;
      }
      assert.equal(await tiles.count(), 1);
      await page.locator('[data-profile-view="grid"]').click();
      assert.equal(await page.locator('#profileGridWrap').isVisible(), true);
      assert.equal(await page.locator('#profileGrid [data-goto-post="reposted-photo"]').count(), 0);
      await page.locator('[data-profile-view="list"]').click();
      await page.locator('#profilePosts [data-load-posts]').scrollIntoViewIfNeeded();
      await page.locator('#profilePosts .post[data-post-id="own-next"]').waitFor();
      assert.equal(await tiles.count(), 2);
      assert.equal(await page.locator('#profileGrid [data-goto-post="quote-next"]').count(), 0);
      assert.equal(await page.locator('#profileGrid [data-goto-post="own-next"]').count(), 1);
      await page.locator('[data-profile-view="reposts"]').click();
      assert.equal(await tiles.count(), 2);
      assert.equal(calls.filter(url => url.includes('/reposts/fragment')).length, 1);
      assert.equal(await page.locator('.sidebar__row[title="Trang cá nhân"]').getAttribute('href'), '/people/tester');
      assert.deepEqual(await page.evaluate(() => [Aloute.profileUrl('123', 6), Aloute.profileUrl('edit', 7),
        Aloute.profileUrl('alice.name', 8), Aloute.profileUrl(null, 9), Aloute.profileUrl('a/b', 1)]),
        ['/people/123', '/people/edit', '/people/alice.name', '/profile/9', '/people/a%2Fb']);
      assert.deepEqual(errors, []);
      await page.close();
      console.log(JSON.stringify({ width, theme, passed: true, gridKeptOwnMediaOnly: true }));
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
