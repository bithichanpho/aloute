const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const feed = fs.readFileSync('src/main/resources/templates/feed/index.html', 'utf8');
  const start = feed.indexOf('<div class="reaction"');
  // Keep the real feed buttons, labels and nesting; Thymeleaf attributes are inert in this fixture.
  const reaction = feed.slice(start, feed.indexOf('<button type="button" class="action action--reply"', start));
  const post = (id, right = false) => `<article class="post ${right ? 'second' : ''}" data-post-id="${id}"><div style="width:100%"><p>Bài viết thử cảm xúc</p><div class="post__actions">${reaction}</div></div></article>`;
  const configurations = [
    { width: 1280, theme: 'dark' }, { width: 1280, theme: 'light' },
    { width: 390, theme: 'dark' }, { width: 390, theme: 'light' },
    { width: 320, theme: 'dark', touch: true }, { width: 390, theme: 'light', touch: true },
    { width: 390, theme: 'dark', reduced: true }
  ];
  try {
    for (const config of configurations) {
      const context = await browser.newContext({ viewport: { width: config.width, height: 850 },
        hasTouch: !!config.touch, isMobile: !!config.touch, reducedMotion: config.reduced ? 'reduce' : 'no-preference' });
      const page = await context.newPage(); page.setDefaultTimeout(7000);
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let serverType = null, calls = [], failNext = false, pendingRoute = null, hold = false;
      await context.addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf', url: 'http://aloute.test' }]);
      const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/posts/post-1/react') {
          assert.equal(route.request().method(), 'POST');
          assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf');
          const type = route.request().postDataJSON().type; calls.push(type);
          if (failNext) { failNext = false; return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại sau"}' }); }
          if (hold) { pendingRoute = route; return; }
          serverType = type; return reply(route, { currentUserReaction: type, totalCount: type ? 1 : 0 });
        }
        if (path.startsWith('/api/')) return reply(route, []);
        if (['/js/app.js', '/css/main.css', '/css/theme.css'].includes(path)) return route.fulfill({ path: 'src/main/resources/static' + path });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="vi" data-bs-theme="${config.theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/css/main.css"><link rel="stylesheet" href="/css/theme.css"><style>main{max-width:700px;margin:160px auto 0;padding:0 12px}.second .post__actions{justify-content:flex-end}</style></head><body data-current-user-id="1"><main>${post('post-1')}${post('post-2', true)}<button id="outside">Ngoài thanh cảm xúc</button></main><script src="/js/app.js"></script></body></html>` });
      });
      await page.goto('http://aloute.test/feed');
      const first = page.locator('[data-post-id="post-1"]');
      const trigger = first.locator('.action--like'); const picker = first.locator('.reaction-picker');
      const choice = type => picker.locator(`[data-reaction="${type}"]`);
      const open = async () => {
        if (config.touch) {
          const bounds = await trigger.boundingBox();
          const cdp = await context.newCDPSession(page);
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 }] });
          await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .reaction-picker').classList.contains('reaction-picker--open'));
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
          await cdp.detach();
        } else await trigger.hover();
        await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .action--like').getAttribute('aria-expanded') === 'true');
      };
      assert.equal(await picker.getAttribute('aria-hidden'), 'true');
      await open();
      assert.equal(await choice('LOVE').getAttribute('aria-label'), 'Yêu thích');
      const selected = choice('LOVE');
      if (!config.touch) {
        await selected.hover();
        await page.waitForFunction(() => getComputedStyle(document.querySelector('[data-post-id="post-1"] [data-reaction="LOVE"]'), '::before').opacity === '1');
        const transform = await selected.evaluate(el => getComputedStyle(el).transform);
        assert.notEqual(transform, 'none');
      }
      const bounds = await picker.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= config.width + 1);
      await page.waitForFunction(() => getComputedStyle(document.querySelector('[data-post-id="post-1"] .reaction-picker')).opacity === '1');
      await page.screenshot({ path: `target/reaction-picker-${config.width}-${config.theme}-${config.touch ? 'touch' : config.reduced ? 'reduced' : 'mouse'}.png`, animations: 'disabled' });
      if (config.touch) await selected.tap(); else await selected.click();
      await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .action--like').classList.contains('reacted--LOVE'));
      await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .action--like span[data-raw]').dataset.raw === '1');
      assert.equal(await trigger.getAttribute('aria-expanded'), 'false');
      assert.equal(await picker.getAttribute('aria-hidden'), 'true');
      assert.equal(await picker.evaluate(el => el.inert), true);
      assert.deepEqual(calls, ['LOVE']);

      if (!config.touch) {
        // Keyboard selection and dismissal; focus must not reopen a dismissed menu.
        await trigger.focus(); await trigger.press('ArrowDown');
        assert.equal(await choice('LIKE').evaluate(el => el === document.activeElement), true);
        await choice('LIKE').press('End');
        assert.equal(await choice('ANGRY').evaluate(el => el === document.activeElement), true);
        await choice('ANGRY').press('Home'); await choice('LIKE').press('ArrowRight');
        assert.equal(await choice('LOVE').getAttribute('aria-checked'), 'true');
        await choice('LOVE').press('Escape');
        assert.equal(await trigger.getAttribute('aria-expanded'), 'false');
        await page.waitForFunction(() => getComputedStyle(document.querySelector('[data-post-id="post-1"] .reaction-picker')).visibility === 'hidden');

        // API failure rolls the optimistic UI back; retry keeps the existing request contract.
        failNext = true; await trigger.press('ArrowDown'); await choice('WOW').press('Enter');
        await page.waitForFunction(() => document.body.textContent.includes('Thử lại sau'));
        assert.equal(await trigger.evaluate(el => el.classList.contains('reacted--LOVE')), true);
        await trigger.press('ArrowDown'); await choice('WOW').press('Enter');
        await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .action--like').classList.contains('reacted--WOW'));

        // Changing selection while a request is pending still drains the existing reaction queue.
        hold = true; await trigger.press('ArrowDown'); await choice('SAD').press('Enter');
        await page.waitForTimeout(30); assert.ok(pendingRoute);
        await trigger.press('ArrowDown'); await choice('ANGRY').press('Enter');
        hold = false; serverType = 'SAD';
        await reply(pendingRoute, { currentUserReaction: 'SAD', totalCount: 1 });
        await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .action--like').classList.contains('reacted--ANGRY'));
        await page.waitForTimeout(50); assert.equal(serverType, 'ANGRY');
        assert.deepEqual(calls, ['LOVE', 'WOW', 'WOW', 'SAD', 'ANGRY']);

        await trigger.press('ArrowDown');
        const other = page.locator('[data-post-id="post-2"] .action--like');
        await other.focus(); await other.press('ArrowDown');
        assert.equal(await trigger.getAttribute('aria-expanded'), 'false');
        const otherBounds = await page.locator('[data-post-id="post-2"] .reaction-picker').boundingBox();
        assert.ok(otherBounds.x + otherBounds.width <= config.width + 1);
        await page.locator('#outside').click();
        assert.equal(await page.locator('.reaction-picker--open').count(), 0);
        await page.locator('main').evaluate(el => { el.style.marginTop = '0px'; });
        await page.waitForFunction(() => document.querySelector('[data-post-id="post-1"] .action--like').getBoundingClientRect().top < 110);
        await trigger.focus(); await trigger.press('ArrowDown');
        assert.ok(await picker.evaluate(el => el.classList.contains('reaction-picker--below')));
        const below = await picker.boundingBox(), button = await trigger.boundingBox();
        assert.ok(below.y >= button.y + button.height);
        await choice('LIKE').press('Escape');
        if (config.reduced) assert.ok(await picker.evaluate(el => getComputedStyle(el).transitionDuration.split(',').every(value => parseFloat(value) <= .001)));
      } else {
        // A normal tap retains the existing remove-reaction behavior after a long press choice.
        await trigger.tap();
        await page.waitForFunction(() => !document.querySelector('[data-post-id="post-1"] .action--like').classList.contains('is-reacted'));
        assert.deepEqual(calls, ['LOVE', null]);
      }
      assert.deepEqual(errors, []);
      console.log('PASS: ' + JSON.stringify(config));
      await context.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
