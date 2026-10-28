const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Exercise the real interaction code against isolated HTTP fixtures, never a user database.
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const types = ['LIKE', 'LOVE', 'HAHA', 'WOW', 'SAD', 'ANGRY'];
  try {
    for (const width of [1280, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 850 }, hasTouch: width === 390, isMobile: width === 390 });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'reaction-fixture-csrf', url: 'http://aloute.test' }]);
      let initial, confirmed, calls, fail, delay;
      await page.route('http://aloute.test/**', async route => {
        const request = route.request(), path = new URL(request.url()).pathname;
        if (path === '/js/app.js') return route.fulfill({ contentType: 'application/javascript', body: fs.readFileSync('src/main/resources/static/js/app.js') });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path === '/css/main.css') return route.fulfill({ contentType: 'text/css', body: fs.readFileSync('src/main/resources/static/css/main.css') });
        if (path === '/api/posts/post-fixture/react') {
          assert.equal(request.method(), 'POST');
          assert.equal(request.headers()['x-xsrf-token'], 'reaction-fixture-csrf');
          const type = request.postDataJSON().type;
          assert.ok(type === null || types.includes(type));
          calls.push(type);
          if (delay) await new Promise(resolve => setTimeout(resolve, delay));
          if (fail) return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại"}' });
          confirmed = type;
          return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: {
            currentUserReaction: confirmed, totalCount: confirmed ? 8 : 7, reactionCounts: {}
          } }) });
        }
        if (path.startsWith('/api/')) return route.fulfill({ contentType: 'application/json', body: '{"success":true,"data":{"count":0}}' });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="/css/main.css"></head><body data-current-user-id="7">
          <article class="post" data-post-id="post-fixture" style="margin:140px 20px 0"><div class="post__actions"><div class="reaction">
            <button type="button" class="action action--like ${confirmed ? 'is-reacted reacted--' + confirmed : ''}"><span class="action--like__emoji"></span><span data-raw="${confirmed ? 8 : 7}">${confirmed ? 8 : 7}</span></button>
            <div class="reaction-picker" role="menu">${types.map(type => `<button type="button" class="reaction-picker__emoji" data-reaction="${type}" title="${type}">👍</button>`).join('')}</div>
          </div></div></article><script src="/js/app.js"></script></body></html>` });
      });
      const reset = async type => {
        initial = confirmed = type; calls = []; fail = false; delay = 0;
        await page.goto('http://aloute.test/feed');
      };
      for (const type of types) for (const action of ['button', 'same-emoji']) {
        await reset(type);
        const settled = page.waitForResponse(response => response.url().endsWith('/react') && response.status() === 200);
        if (action === 'button') await page.locator('.action--like').click();
        else {
          if (width === 390) {
            await page.locator('.action--like').dispatchEvent('touchstart');
            await page.locator('.reaction-picker--open').waitFor();
            await page.locator('.action--like').dispatchEvent('touchend');
          } else await page.locator('.reaction').hover();
          await page.locator('.reaction-picker--open').waitFor();
          await page.locator(`[data-reaction="${type}"]`).click();
        }
        await page.waitForFunction(() => document.querySelector('.action--like [data-raw]').dataset.raw === '7');
        await settled;
        assert.deepEqual(calls, [null]);
        assert.equal(confirmed, null);
        assert.equal(await page.locator('.action--like').evaluate(button => button.classList.contains('is-reacted')), false);
        assert.equal(await page.locator('.action--like__emoji').textContent(), '');
        await page.reload();
        assert.equal(await page.locator('.action--like').evaluate(button => button.classList.contains('is-reacted')), false);
        assert.equal(await page.locator('.action--like [data-raw]').getAttribute('data-raw'), '7');
        console.log(`PASS: ${width}/${type}/${action}`);
      }
      await reset('LOVE'); fail = true;
      await page.locator('.action--like').click();
      await page.waitForFunction(() => document.querySelector('.action--like').classList.contains('reacted--LOVE'));
      assert.equal(await page.locator('.action--like [data-raw]').getAttribute('data-raw'), '8');
      assert.equal(confirmed, 'LOVE'); assert.deepEqual(calls, [null]);
      console.log(`PASS: ${width}/network-failure-restores-reaction`);

      await reset('LOVE'); delay = 100;
      const lastResponse = page.waitForResponse(response => response.url().endsWith('/react') && response.request().postDataJSON().type === 'LIKE');
      await page.evaluate(() => { const button = document.querySelector('.action--like'); button.click(); button.click(); });
      await page.waitForFunction(() => document.querySelector('.action--like').classList.contains('reacted--LIKE') && document.querySelector('.action--like [data-raw]').dataset.raw === '8');
      await page.waitForFunction(() => !document.querySelector('.reaction-picker--open'));
      await lastResponse;
      assert.deepEqual(calls, [null, 'LIKE']); assert.equal(confirmed, 'LIKE');
      assert.deepEqual(errors, []);
      console.log(`PASS: ${width}/rapid-remove-then-add-converges`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
