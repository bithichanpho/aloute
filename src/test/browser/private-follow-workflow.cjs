const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Actual application assets, isolated HTTP fixtures; no user database mutations.
(async () => {
  const assets = {
    '/js/app.js': ['application/javascript', fs.readFileSync('src/main/resources/static/js/app.js', 'utf8')],
    '/js/features.js': ['application/javascript', fs.readFileSync('src/main/resources/static/js/features.js', 'utf8')],
    '/css/features.css': ['text/css', fs.readFileSync('src/main/resources/static/css/features.css', 'utf8')]
  };
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const actions = surface => `<div class="follow-request-actions" data-follow-request-actions="6:1" data-follow-profile="${surface === 'profile'}"><button data-action="accept-follow" data-follow-decision="accept" data-request-id="6:1">Chấp nhận</button><button data-action="reject-follow" data-follow-decision="reject" data-request-id="6:1">Từ chối</button></div>`;
  const account = '<a class="feature-request-account" href="/profile/6"><img class="follow-request-avatar" src="/alice.png" alt="Ảnh đại diện"><div class="follow-request-identity"><strong>Alice</strong><small>@alice</small></div></a>';
  const html = body => `<!doctype html><html><head><link rel="stylesheet" href="/css/features.css"></head><body data-current-user-id="1">${body}<script src="/js/app.js"></script><script src="/js/features.js"></script></body></html>`;
  const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  const infrastructure = async route => {
    const path = new URL(route.request().url()).pathname;
    if (assets[path]) { await route.fulfill({ contentType: assets[path][0], body: assets[path][1] }); return true; }
    if (path.endsWith('.js')) { await route.fulfill({ contentType: 'application/javascript', body: '' }); return true; }
    if (path.endsWith('.png')) { await route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZkAAAAASUVORK5CYII=', 'base64') }); return true; }
    return false;
  };
  const scenarios = [];
  for (const surface of ['settings', 'follow-requests', 'profile'])
    for (const decision of ['accept', 'reject']) scenarios.push({ surface, decision, mode: 'success' });
  scenarios.push({ surface: 'follow-requests', decision: 'accept', mode: 'retry' }, { surface: 'follow-requests', decision: 'reject', mode: 'stale' });
  try {
    for (const { surface, decision, mode } of scenarios) {
      const page = await browser.newPage({ viewport: { width: decision === 'reject' ? 390 : 1280, height: 800 } });
      const errors = [], calls = [];
      let mutations = 0, resolved = false;
      page.on('pageerror', error => errors.push(error.message));
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'test-csrf', url: 'http://aloute.test' }]);
      await page.route('http://aloute.test/**', async route => {
        if (await infrastructure(route)) return;
        const path = new URL(route.request().url()).pathname;
        if (path.startsWith('/api/')) {
          calls.push({ path, method: route.request().method() });
          if (path.startsWith('/api/follow-requests/') && ['PUT', 'DELETE'].includes(route.request().method())) {
            mutations++;
            await new Promise(resolve => setTimeout(resolve, 70));
            if ((mode === 'retry' && mutations === 1) || mode === 'stale') return route.fulfill({ status: mode === 'stale' ? 404 : 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại"}' });
            resolved = true;
          }
          return reply(route, 0);
        }
        let body = resolved ? '<p>Yêu cầu đã xử lý</p>' : surface === 'settings'
          ? `<div id="featureContent"><div id="followRequestList"><div class="feature-list__row" data-request-id="6:1">${account}${actions(surface)}</div></div></div>`
          : surface === 'follow-requests' ? `<div id="featureContent"><div id="followRequestList"><div class="feature-list__row" data-request-id="6:1">${account}${actions(surface)}</div></div></div>`
          : `<div class="profile-cover">${account}${actions(surface)}</div>`;
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html(body) });
      });
      await page.goto('http://aloute.test/' + surface);
      assert.equal(await page.locator('a[href="/profile/6"] img').count(), 1);
      const reloaded = surface === 'profile' ? page.waitForEvent('load') : null;
      await page.evaluate(choice => {
        document.querySelector(`[data-follow-decision="${choice}"]`).click();
        document.querySelector(`[data-follow-decision="${choice === 'accept' ? 'reject' : 'accept'}"]`).click();
      }, decision);
      if (mode === 'retry') {
        await page.waitForFunction(() => !document.querySelector('[data-follow-decision="accept"]').disabled);
        await page.locator('[data-follow-decision="accept"]').click();
      }
      if (reloaded) await reloaded;
      await page.waitForFunction(() => !document.querySelector('[data-follow-decision]'));
      assert.equal(mutations, mode === 'retry' ? 2 : 1);
      assert.ok(calls.filter(c => c.path.startsWith('/api/follow-requests/') && ['PUT', 'DELETE'].includes(c.method)).every(c => c.method === (decision === 'accept' ? 'PUT' : 'DELETE')));
      if (surface === 'settings') assert.equal(await page.locator('.feature-list__row').count(), 0);
      if (surface === 'follow-requests') {
        assert.equal(await page.locator('#followRequestList .feature-list__row').count(), 0);
        assert.ok(!calls.some(c => c.path.startsWith('/api/notifications/') && c.method === 'POST'), 'Requests use their own page without notification mutations');
      }
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ surface, decision, mode, result: 'PASS' }));
      await page.close();
    }
    for (const width of [1280, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 800 } });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.route('http://aloute.test/**', async route => {
        if (await infrastructure(route)) return;
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/follow-requests/cursor') return reply(route, { items: [
          { id: '6:1', followerId: 6, followerFullName: 'Alice', followerUsername: 'alice', followerAvatarUrl: '/alice.png' },
          { id: '7:1', followerId: 7, followerFullName: ' ', followerUsername: 'linh' },
          { id: '8:1', followerId: 8 },
          { id: '9:1', followerId: 9, followerFullName: '<img src=x onerror=alert(1)>', followerUsername: 'safe' }
        ], nextCursor: '', hasMore: false });
        if (path.startsWith('/api/')) return reply(route, 0);
        if (path === '/profile/6') return route.fulfill({ contentType: 'text/html', body: '<h1>Alice profile</h1>' });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html('<div id="featureContent"><div id="followRequestList"></div><button data-action="load-more-requests">Xem thêm</button></div>') });
      });
      await page.goto('http://aloute.test/settings');
      await page.locator('[data-action="load-more-requests"]').click();
      await page.waitForFunction(() => document.querySelectorAll('.feature-list__row').length === 4);
      assert.equal(await page.locator('[data-request-id="6:1"] strong').textContent(), 'Alice');
      assert.equal(await page.locator('[data-request-id="7:1"] strong').textContent(), 'linh');
      assert.equal(await page.locator('[data-request-id="8:1"] strong').textContent(), 'Tài khoản không còn khả dụng');
      assert.equal(await page.locator('[data-request-id="8:1"] img').getAttribute('src'), '/images/avatar_default.png');
      assert.equal(await page.locator('[data-request-id="9:1"] strong img').count(), 0);
      assert.equal(await page.locator('[data-request-id="9:1"] strong').textContent(), '<img src=x onerror=alert(1)>');
      await page.locator('[data-request-id="7:1"] [data-follow-decision="accept"]').click();
      await page.waitForFunction(() => !document.querySelector('[data-request-id="7:1"]'));
      await page.locator('a[href="/profile/6"]').click(); await page.waitForURL('http://aloute.test/profile/6');
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ scenario: 'cursor-avatar-labels-actions-profile', width, result: 'PASS' }));
      await page.close();
    }
    for (const scenario of ['create-cancel', 'delayed-cancel', 'delayed-local-decision']) {
      const page = await browser.newPage();
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let reads = 0;
      await page.route('http://aloute.test/**', async route => {
        if (await infrastructure(route)) return;
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/follow-requests/cursor') {
          reads++;
          if (scenario !== 'create-cancel') await new Promise(resolve => setTimeout(resolve, 200));
          return reply(route, { items: [{ id: '6:1', followerId: 6, followerUsername: 'alice',
            followerFullName: 'Alice', followerAvatarUrl: '/alice.png' }], hasMore: false });
        }
        if (path.startsWith('/api/')) return reply(route, 0);
        const initial = scenario === 'delayed-local-decision'
          ? `<div class="feature-list__row" data-request-id="6:1">${account}${actions('settings')}</div>` : '';
        return route.fulfill({ contentType: 'text/html; charset=utf-8',
          body: html(`<div id="featureContent"><div id="followRequestList">${initial}</div></div>`) });
      });
      await page.goto('http://aloute.test/settings');
      const hint = async (eventType, targetId = 1) => page.evaluate(({ eventType, targetId }) => {
        document.dispatchEvent(new CustomEvent('aloute:follow-requests-changed',
          { detail: { eventType, followerId: 6, targetId } }));
      }, { eventType, targetId });
      await hint('REQUEST_CREATED', 99);
      await page.waitForTimeout(40); assert.equal(reads, 0);
      await hint('REQUEST_CREATED');
      await page.waitForFunction(() => true);
      for (let attempts = 0; reads === 0 && attempts < 20; attempts++) await page.waitForTimeout(10);
      assert.equal(reads, 1);
      if (scenario === 'create-cancel') {
        await page.locator('#followRequestList [data-request-id="6:1"] strong').waitFor();
        assert.equal(await page.locator('#followRequestList strong').innerText(), 'Alice');
        await hint('REQUEST_CANCELLED');
      } else if (scenario === 'delayed-local-decision') {
        await page.locator('[data-follow-decision="accept"]').click();
      } else await hint('REQUEST_CANCELLED');
      await page.waitForTimeout(300);
      assert.equal(await page.locator('#followRequestList .feature-list__row').count(), 0);
      assert.equal(await page.locator('#followRequestList .feature-empty').count(), 1);
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ scenario, result: 'PASS' }));
      await page.close();
    }
    for (const scenario of ['private-unfollow', 'private-pending', 'public-follow']) {
      const page = await browser.newPage(); let navigations = 0;
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.route('http://aloute.test/**', async route => {
        if (await infrastructure(route)) return;
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith('/follow')) return reply(route, scenario === 'public-follow');
        if (path.endsWith('/follow-status')) return reply(route, scenario === 'private-pending' ? 'PENDING' : scenario === 'public-follow' ? 'FOLLOWING' : 'NONE');
        if (path.startsWith('/api/')) return reply(route, 0);
        navigations++;
        const body = navigations > 1 ? '<p>Đây là tài khoản riêng tư.</p>' : `<div class="profile-cover">${scenario.startsWith('private') ? '<span class="profile-cover__badge">Riêng tư</span>' : ''}<button class="btn-follow" data-user-id="6">Theo dõi</button></div>`;
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html(body) });
      });
      await page.goto('http://aloute.test/profile/6');
      const reloaded = scenario.startsWith('private') ? page.waitForEvent('load') : null;
      await page.locator('.btn-follow').click();
      if (reloaded) { await reloaded; assert.equal(navigations, 2); }
      else { await page.waitForFunction(() => document.querySelector('.btn-follow').classList.contains('is-following')); assert.equal(navigations, 1); }
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ scenario, result: 'PASS' }));
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
