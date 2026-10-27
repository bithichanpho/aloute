const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Real Thymeleaf render and application assets; HTTP/STOMP fixtures never write to a live account.
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = [], mutations = [], scenarios = [];
  let requests = [{ id: '6:1', followerId: 6, followerUsername: 'minhan', followerFullName: 'Minh An' }];
  let slowNextPage = false;
  page.on('pageerror', error => errors.push(error.message));
  const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  try {
    await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'test-csrf', url: 'http://aloute.test' }]);
    await page.addInitScript(() => {
      window.__mergeSubscriptions = {};
      window.SockJS = function () {};
      window.Stomp = { over: () => ({
        connect: (_, ready) => setTimeout(ready, 0),
        subscribe: (destination, handler) => { window.__mergeSubscriptions[destination] = handler; },
        send() {}, disconnect() {}
      }) };
    });
    await page.route('http://aloute.test/**', async route => {
      const url = new URL(route.request().url());
      if (url.pathname === '/follow-requests') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: fs.readFileSync('target/follow-requests-template.html') });
      if (url.pathname === '/api/follow-requests/unread-count') return reply(route, { count: requests.length });
      if (url.pathname === '/api/follow-requests/cursor') {
        const snapshot = [...requests];
        if (slowNextPage) { slowNextPage = false; await new Promise(resolve => setTimeout(resolve, 250)); }
        return reply(route, { items: snapshot, nextCursor: '', hasMore: false });
      }
      if (url.pathname.startsWith('/api/follow-requests/') && route.request().method() !== 'GET') {
        assert.equal(route.request().headers()['x-xsrf-token'], 'test-csrf');
        const id = decodeURIComponent(url.pathname.split('/').at(-1));
        mutations.push({ id, method: route.request().method() });
        requests = requests.filter(request => request.id !== id);
        return reply(route, null);
      }
      if (url.pathname.startsWith('/api/')) return reply(route, 0);
      const asset = path.join('src/main/resources/static', url.pathname);
      if (url.pathname.endsWith('.js') && !['/js/app.js', '/js/features.js', '/js/ui.js'].includes(url.pathname))
        return route.fulfill({ contentType: 'application/javascript', body: '' });
      if (fs.existsSync(asset) && fs.statSync(asset).isFile()) {
        const types = { '.js': 'application/javascript', '.css': 'text/css', '.png': 'image/png', '.ico': 'image/x-icon' };
        return route.fulfill({ contentType: types[path.extname(asset)] || 'application/octet-stream', body: fs.readFileSync(asset) });
      }
      return route.fulfill({ status: 204, body: '' });
    });
    await page.goto('http://aloute.test/follow-requests');
    await page.waitForFunction(() => window.__mergeSubscriptions['/user/queue/follow-requests']);
    assert.equal(await page.locator('#followRequestList [data-follow-request-actions="6:1"]').count(), 1);
    assert.equal(await page.locator('#followRequestList script').count(), 0);
    scenarios.push('real template, escaped labels and shared decision controls');

    const hint = async payload => page.evaluate(value => window.__mergeSubscriptions['/user/queue/follow-requests']({ body: JSON.stringify(value) }), payload);
    requests.push({ id: '7:1', followerId: 7, followerUsername: 'alice', followerFullName: 'Alice' });
    await hint({ followerId: 7, targetId: 1, eventType: 'REQUEST_CREATED', status: 'PENDING' });
    await page.waitForSelector('#followRequestList .feature-list__row[data-request-id="7:1"]');
    requests = requests.filter(request => request.id !== '7:1');
    await hint({ followerId: 7, targetId: 1, eventType: 'REQUEST_CANCELLED', status: 'NONE' });
    assert.equal(await page.locator('#followRequestList .feature-list__row[data-request-id="7:1"]').count(), 0);
    scenarios.push('incoming create/cancel updates the dedicated page');

    requests.push({ id: '8:1', followerId: 8, followerUsername: 'delayed', followerFullName: 'Delayed' });
    slowNextPage = true;
    await hint({ followerId: 8, targetId: 1, eventType: 'REQUEST_CREATED', status: 'PENDING' });
    await page.waitForTimeout(50);
    requests = requests.filter(request => request.id !== '8:1');
    await hint({ followerId: 8, targetId: 1, eventType: 'REQUEST_CANCELLED', status: 'NONE' });
    await page.waitForTimeout(350);
    assert.equal(await page.locator('#followRequestList .feature-list__row[data-request-id="8:1"]').count(), 0);
    scenarios.push('a delayed fetch cannot resurrect a canceled request');

    await page.evaluate(() => { const button = document.createElement('button'); button.className = 'btn-follow'; button.dataset.userId = '2'; button.id = 'fixture-follow'; document.body.append(button); });
    await hint({ followerId: 1, targetId: 2, eventType: 'STATE_CHANGED', status: 'PENDING' });
    assert.equal(await page.locator('#fixture-follow').getAttribute('data-follow-status'), 'PENDING');
    await hint({ followerId: 1, targetId: 2, eventType: 'STATE_CHANGED', status: 'FOLLOWING' });
    assert.equal(await page.locator('#fixture-follow').getAttribute('data-follow-status'), 'FOLLOWING');
    await page.locator('#fixture-follow').evaluate(element => element.remove());
    scenarios.push('requester tabs keep follow status synchronized');

    await page.evaluate(() => {
      window.__notificationEvents = 0;
      document.addEventListener('aloute:notification', () => window.__notificationEvents++);
      const body = JSON.stringify({ id: 99, type: 'FOLLOW_REQUEST_ACCEPTED', content: 'Đã chấp nhận yêu cầu' });
      window.__mergeSubscriptions['/user/queue/notifications']({ body });
      window.__mergeSubscriptions['/user/queue/notifications']({ body });
    });
    assert.equal(await page.evaluate(() => window.__notificationEvents), 1);
    scenarios.push('duplicate notification ids do not repeat live UI delivery');

    await page.locator('[data-follow-request-actions="6:1"] [data-follow-decision="accept"]').click();
    await page.waitForFunction(() => !document.querySelector('#followRequestList .feature-list__row[data-request-id="6:1"]'));
    assert.deepEqual(mutations, [{ id: '6:1', method: 'PUT' }]);
    assert.equal(await page.locator('#followRequestList .feature-empty').count(), 1);
    scenarios.push('accept keeps one authorized request and removes the row');
    for (const width of [1280, 390]) {
      await page.setViewportSize({ width, height: 900 });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      await page.screenshot({ path: `target/follow-request-merge-${width}.png`, fullPage: true });
    }
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: scenarios.length, scenarios, pageErrors: errors }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
