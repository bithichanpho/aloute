const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Actual shared shell + app.js + calls.js. No chat page or popup is opened.
// The broker and physical devices are substituted; subscription and UI routing are production code.
const html = fs.readFileSync('target/search-template.html', 'utf8');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  try {
    for (const path of ['/feed', '/search', '/profile/me', '/saved', '/settings', '/activity']) {
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => {
        window.fixtureClients = []; window.framesSent = [];
        window.SockJS = function () {};
        window.Stomp = { over() {
          const client = {
            subscriptions: new Map(), closed: false,
            connect(headers, ready, failed) { this.fail = failed; setTimeout(ready, 0); },
            subscribe(destination, callback) { this.subscriptions.set(destination, callback); },
            send(destination, headers, body) {
              if (this.closed) throw new Error('Old connection is closed');
              framesSent.push({ destination, client: fixtureClients.indexOf(this), ...JSON.parse(body) });
            }
          };
          fixtureClients.push(client);
          return client;
        } };
        navigator.mediaDevices.getUserMedia = async () => {
          const stream = new MediaStream();
          stream.getTracks = () => [{ stop() {} }];
          return stream;
        };
        window.RTCPeerConnection = class {
          constructor() { this.connectionState = 'new'; }
          addTrack() {}
          async setRemoteDescription(value) { this.remoteDescription = value; }
          async createAnswer() { return { type: 'answer', sdp: 'test-answer' }; }
          async setLocalDescription(value) { this.localDescription = value; }
          close() {}
        };
      });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://aloute.test') return route.fulfill({ status: 204, body: '' });
        if (url.pathname.startsWith('/api/')) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
          success: true, data: url.pathname.includes('ice-servers') ? [{ urls: 'stun:test.invalid' }]
            : url.pathname.includes('unread-count') ? { count: 0 } : []
        }) });
        if (url.pathname.endsWith('.js') && !['/js/app.js', '/js/calls.js'].includes(url.pathname)) {
          return route.fulfill({ contentType: 'application/javascript', body: '' });
        }
        const file = 'src/main/resources/static' + url.pathname;
        if (fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({
          contentType: url.pathname.endsWith('.js') ? 'application/javascript' : url.pathname.endsWith('.css') ? 'text/css' : 'image/png',
          body: fs.readFileSync(file)
        });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      });
      await page.goto('https://aloute.test' + path);
      await page.waitForFunction(() => window.__alouteRealtimeConnected === true);
      assert.equal(await page.locator('#chatList, .friends-chat-window').count(), 0);
      async function receive(callId) {
        await page.evaluate(id => fixtureClients.at(-1).subscriptions.get('/user/queue/call')({ body: JSON.stringify({
          type: 'offer', callId: id, fromUserId: 2, fromFullName: 'Bạn gọi đến', callType: 'audio',
          sdp: { type: 'offer', sdp: 'test-offer' }
        }) }), callId);
        assert.equal(await page.locator('#videoCallModal').isVisible(), true);
        assert.equal(await page.locator('#answerCallBtn').isVisible(), true);
        await page.locator('#answerCallBtn').click();
        await page.waitForFunction(id => framesSent.some(frame => frame.destination === '/app/call.answer' && frame.callId === id), callId,
          { timeout: 5000 });
        await page.locator('#hangupCallBtn').click();
        assert.equal(new URL(page.url()).pathname, path);
      }
      await receive('initial-global');
      if (path === '/feed') {
        await page.evaluate(() => { const client = fixtureClients.at(-1); client.closed = true; client.fail({ body: 'network disconnected' }); });
        await page.waitForFunction(() => fixtureClients.length === 2 && window.__alouteRealtimeConnected === true);
        await receive('after-reconnect');
        assert.equal(await page.evaluate(() => framesSent.find(frame => frame.destination === '/app/call.answer' && frame.callId === 'after-reconnect').client), 1);
      }
      assert.deepEqual(errors, []);
      console.log('PASS: automatic incoming call on ' + path + (path === '/feed' ? ', including reconnect' : ''));
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
