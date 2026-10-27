const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const html = fs.readFileSync('target/compose-audience-template.html', 'utf8')
    .replace(/<script\b[^>]*src="([^"]+)"[^>]*>[\s\S]*?<\/script>/g, (tag, src) => src.startsWith('/js/app.js') ? tag : '');
  const labels = { PUBLIC: 'Công khai', FRIENDS: 'Bạn bè', CLOSE_FRIENDS: 'Bạn thân', ONLY_ME: 'Chỉ mình tôi' };
  try {
    for (const width of [1280, 390]) {
      for (const theme of ['dark', 'light']) {
        const page = await browser.newPage({ viewport: { width, height: 850 } });
        const errors = [], posts = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'compose-fixture-csrf', url: 'http://aloute.test' }]);
        await page.route('**/*', async route => {
          const url = new URL(route.request().url()), path = url.pathname;
          if (url.origin !== 'http://aloute.test') return route.fulfill({ status: 204, body: '' });
          if (path === '/api/posts' && route.request().method() === 'POST') {
            assert.equal(route.request().headers()['x-xsrf-token'], 'compose-fixture-csrf');
            posts.push(route.request().postDataJSON());
            return route.fulfill({ contentType: 'application/json', body: '{"success":true,"data":{"id":"created"}}' });
          }
          if (path.startsWith('/api/')) return route.fulfill({ contentType: 'application/json', body: '{"success":true,"data":{"count":0}}' });
          const asset = 'src/main/resources/static' + path;
          if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: path === '/js/app.js' ? fs.readFileSync(asset) : '' });
          if (path.endsWith('.css') && fs.existsSync(asset)) return route.fulfill({ contentType: 'text/css', body: fs.readFileSync(asset) });
          if (path.endsWith('.png') && fs.existsSync(asset)) return route.fulfill({ contentType: 'image/png', body: fs.readFileSync(asset) });
          if (path.includes('/vendor/')) return route.fulfill({ status: 204, body: '' });
          return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
        });
        for (const [audience,label] of Object.entries(labels)) {
          await page.goto('http://aloute.test/compose');
          await page.evaluate(value => document.documentElement.setAttribute('data-bs-theme', value), theme);
          await page.locator('[data-audience-open]').click();
          const menu = page.getByRole('listbox', { name: 'Ai có thể xem bài viết' });
          assert.equal(await menu.getByRole('option').count(), 4);
          assert.equal(await menu.locator('svg').count(), 4);
          assert.equal(await page.locator('#postVisibility').inputValue(), 'PUBLIC');
          if (audience === 'PUBLIC') {
            await page.keyboard.press('ArrowDown');
            assert.equal(await page.evaluate(() => document.activeElement.dataset.audienceValue), 'FRIENDS');
            await page.keyboard.press('Escape');
            await menu.waitFor({ state: 'hidden' });
            assert.equal(await page.locator('#postVisibility').inputValue(), 'PUBLIC', 'Escape must retain previous audience');
            await page.locator('[data-audience-open]').click();
            await page.locator('#composeContent').click();
            await menu.waitFor({ state: 'hidden' });
            await page.waitForFunction(() => document.querySelector('[data-audience-open]').getAttribute('aria-expanded') === 'false');
            await page.locator('[data-audience-open]').click();
          }
          const box = await menu.boundingBox();
          const anchor = await page.locator('[data-audience-open]').boundingBox();
          assert.ok(box.x >= 0 && box.x + box.width <= width);
          assert.ok(box.width <= 220 && box.height <= 190, 'Audience selector must stay compact');
          assert.ok(Math.abs(box.y - (anchor.y + anchor.height + 6)) < 2, 'Menu opens below trigger');
          if (audience === 'CLOSE_FRIENDS') await page.screenshot({ path: `target/compose-audience-dropdown-${width}-${theme}.png` });
          const before = posts.length;
          if (audience === 'FRIENDS') {
            await page.keyboard.press('ArrowDown');
            await page.keyboard.press('Enter');
          } else await menu.getByRole('option', { name: label, exact: true }).click();
          await menu.waitFor({ state: 'hidden' });
          assert.equal(await page.locator('#postVisibility').inputValue(), audience);
          assert.equal(await page.locator('[data-audience-current-label]').innerText(), label);
          assert.equal(await page.locator('[data-audience-open]').getAttribute('aria-expanded'), 'false');
          assert.equal(posts.length, before, 'Picking an audience must not publish a post');
          await page.locator('[data-audience-open]').click();
          assert.equal(await menu.getByRole('option', { name: label, exact: true }).getAttribute('aria-selected'), 'true');
          await page.locator('[data-audience-open]').click();
          await menu.waitFor({ state: 'hidden' });
          await page.locator('#composeContent').fill('Audience ' + audience);
          await Promise.all([page.waitForURL('**/feed'), page.locator('#composeSubmit').click()]);
          assert.equal(posts.length, before + 1);
          assert.equal(posts.at(-1).visibility, audience);
          assert.equal(posts.at(-1).content, 'Audience ' + audience);
          console.log(`PASS: ${width}/${theme}/${audience}`);
        }
        assert.deepEqual(errors, []); await page.close();
      }
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
