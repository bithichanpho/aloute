const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Rendered application shell with the production call runtime, popup renderer and styles.
// Only server replies, physical media and peer transport are substituted.
const shell = fs.readFileSync('target/search-template.html', 'utf8');
const html = shell.replace('<section class="content-inner search-page">', `<section class="content-inner search-page">
  <div id="friendsPanel"><button data-friend-chat-open data-user-id="2" data-username="friend"
  data-fullname="Phạm Quý Vỹ" data-avatar="/images/avatar_default.png" data-online="true">Mở chat bạn</button></div>
  <button data-group-chat-open data-group-id="9" data-group-name="Nhóm thử">Mở chat nhóm</button>`);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const results = [];
  try {
    async function fixture(width = 1280, theme = 'dark') {
      const page = await browser.newPage({ viewport: { width, height: 900 } });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => {
        window.framesSent = []; window.toasts = []; window.peers = []; window.mediaRequests = [];
        window.__alouteRealtimeConnected = true;
        const client = { send(destination, headers, body) { framesSent.push({ destination, ...JSON.parse(body) }); } };
        const history = ['ENDED', 'DECLINED', 'CANCELLED', 'MISSED'].map((status, index) => ({
          id: 'call-' + index, senderId: index === 3 ? 2 : 1, receiverId: index === 3 ? 1 : 2,
          messageType: 'CALL', callType: index === 2 ? 'video' : 'audio', callStatus: status,
          callDurationSeconds: status === 'ENDED' ? 87 : 0, content: '', sentAt: '2026-10-28T07:00:00'
        }));
        window.Aloute = {
          onRealtimeReady(callback) { callback(client); }, refreshNavBadges() {},
          toast: (...args) => toasts.push(args),
          escapeHtml: value => String(value ?? '').replace(/[&<>"']/g, ch => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
          }[ch])),
          api: async path => path.includes('ice-servers') ? [{ urls: 'stun:stun.example.invalid' }]
            : path === '/api/chat/messages/2' ? history : []
        };
        navigator.mediaDevices.getUserMedia = async options => {
          mediaRequests.push(options);
          const stream = new MediaStream();
          const audio = { enabled: true, stop() { this.stopped = true; } };
          const video = options.video ? { enabled: true, stop() { this.stopped = true; } } : null;
          stream.getTracks = () => video ? [audio, video] : [audio];
          stream.getAudioTracks = () => [audio]; stream.getVideoTracks = () => video ? [video] : [];
          window.lastStream = stream;
          return stream;
        };
        window.RTCPeerConnection = class {
          constructor() { this.connectionState = 'new'; peers.push(this); }
          addTrack() {}
          async createOffer() { return { type: 'offer', sdp: 'fixture-offer' }; }
          async createAnswer() { return { type: 'answer', sdp: 'fixture-answer' }; }
          async setLocalDescription(value) { this.localDescription = value; }
          async setRemoteDescription(value) { this.remoteDescription = value; }
          async addIceCandidate() {}
          close() { this.connectionState = 'closed'; }
        };
      });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://aloute.test') return route.fulfill({ status: 204, body: '' });
        const file = 'src/main/resources/static' + url.pathname;
        if (url.pathname.endsWith('.js') && !['/js/calls.js', '/js/friends-panel.js'].includes(url.pathname)) {
          return route.fulfill({ contentType: 'application/javascript', body: '' });
        }
        if (fs.existsSync(file) && fs.statSync(file).isFile()) {
          const type = url.pathname.endsWith('.js') ? 'application/javascript'
            : url.pathname.endsWith('.css') ? 'text/css' : url.pathname.endsWith('.woff2') ? 'font/woff2' : 'image/png';
          return route.fulfill({ contentType: type, body: fs.readFileSync(file) });
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html,
          headers: { 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; media-src 'self' blob:;" } });
      });
      await page.goto('https://aloute.test/feed');
      await page.evaluate(value => document.documentElement.setAttribute('data-bs-theme', value), theme);
      await page.locator('[data-friend-chat-open]').click();
      await page.locator('.friends-chat-window .bubble__call').first().waitFor();
      return { page, errors };
    }

    for (const width of [1280, 390]) for (const theme of ['dark', 'light']) {
      const { page, errors } = await fixture(width, theme);
      const popup = page.locator('.friends-chat-window');
      assert.equal(await popup.getByRole('button', { name: 'Gọi thoại', exact: true }).count(), 1);
      assert.equal(await popup.getByRole('button', { name: 'Gọi video', exact: true }).count(), 1);
      assert.equal(await popup.locator('.bubble__call').count(), 4);
      const text = await popup.innerText();
      for (const label of ['1 phút 27 giây', 'Bị từ chối', 'Đã hủy', 'Cuộc gọi thoại nhỡ']) assert.ok(text.includes(label), label);
      const box = await popup.boundingBox();
      const buttons = await popup.locator('.friends-chat-window__header-actions').boundingBox();
      assert.ok(box.x >= 0 && box.x + box.width <= width);
      assert.ok(buttons.x >= box.x && buttons.x + buttons.width <= box.x + box.width);
      await page.screenshot({ path: `target/popup-calls-${width}-${theme}.png`, animations: 'disabled' });
      await popup.getByRole('button', { name: 'Gọi thoại', exact: true }).click();
      await page.waitForFunction(() => framesSent.some(frame => frame.destination === '/app/call.offer'));
      assert.deepEqual(await page.evaluate(() => mediaRequests[0]), { video: false, audio: true });
      assert.equal(await page.locator('#audioCallProfile').isVisible(), true);
      assert.equal(await page.locator('#videoCallGrid').isVisible(), false);
      assert.equal(await page.locator('#toggleCameraBtn').isVisible(), false);
      assert.equal(await page.locator('#videoCallModal').count(), 1);
      assert.ok((await page.locator('#videoCallTitle').innerText()).includes('Phạm Quý Vỹ'));
      assert.equal(new URL(page.url()).pathname, '/feed');
      await page.locator('#toggleMicBtn').click();
      assert.equal(await page.evaluate(() => lastStream.getAudioTracks()[0].enabled), false);
      await page.screenshot({ path: `target/popup-audio-call-${width}-${theme}.png` });
      await page.locator('#hangupCallBtn').click();
      assert.equal(await page.locator('#videoCallModal').isVisible(), false);
      assert.equal(await page.evaluate(() => lastStream.getTracks().every(track => track.stopped)), true);
      await popup.getByRole('button', { name: 'Gọi video', exact: true }).click();
      await page.waitForFunction(() => framesSent.filter(frame => frame.destination === '/app/call.offer').length === 2);
      assert.deepEqual(await page.evaluate(() => mediaRequests[1]), { video: true, audio: true });
      assert.equal(await page.locator('#videoCallGrid').isVisible(), true);
      assert.equal(await page.locator('#toggleMicBtn').getAttribute('aria-pressed'), 'false');
      assert.equal(await page.locator('#toggleCameraBtn').isVisible(), true);
      await page.locator('#toggleCameraBtn').click();
      assert.equal(await page.evaluate(() => lastStream.getVideoTracks()[0].enabled), false);
      await page.locator('#hangupCallBtn').click();
      await popup.locator('[data-call-again]').first().focus();
      await page.keyboard.press('Enter');
      await page.waitForFunction(() => framesSent.filter(frame => frame.destination === '/app/call.offer').length === 3);
      assert.equal(await page.evaluate(() => framesSent.filter(frame => frame.destination === '/app/call.offer').at(-1).targetUserId), 2);
      assert.deepEqual(errors, []);
      results.push(`${width}/${theme}: history, audio, video, controls, keyboard redial`);
      await page.close();
    }

    {
      const { page, errors } = await fixture();
      const signal = detail => page.evaluate(value => document.dispatchEvent(new CustomEvent('aloute:call-signal', { detail: value })), detail);
      // Receiving a call also works when no popup is open.
      await page.getByRole('button', { name: 'Đóng cửa sổ chat', exact: true }).click();
      const incoming = { type: 'offer', callId: 'incoming-popup', fromUserId: 2, fromFullName: 'Phạm Quý Vỹ',
        callType: 'audio', sdp: { type: 'offer', sdp: 'fixture' } };
      await signal(incoming);
      assert.equal(await page.locator('#answerCallBtn').isVisible(), true);
      await page.locator('#answerCallBtn').click();
      await page.waitForFunction(() => framesSent.some(frame => frame.destination === '/app/call.answer'));
      await page.evaluate(() => {
        window.originalCallModal = document.getElementById('videoCallModal');
        window.originalCallVideo = document.getElementById('localVideo');
        document.body.innerHTML = document.body.innerHTML;
        document.dispatchEvent(new Event('aloute:navigated'));
      });
      assert.equal(await page.evaluate(() => document.getElementById('videoCallModal') === originalCallModal
        && document.getElementById('localVideo') === originalCallVideo && originalCallVideo.srcObject === lastStream), true);
      assert.equal(await page.locator('#videoCallModal').isVisible(), true);
      await page.locator('#hangupCallBtn').click();
      await signal({ ...incoming, callId: 'rejected-popup' });
      await page.locator('#rejectCallBtn').click();
      assert.equal(await page.evaluate(() => framesSent.at(-1).reason), 'rejected');
      assert.deepEqual(errors, []);
      results.push('incoming answer/reject outside popup and active call survives soft navigation');
      await page.close();
    }
    console.log(JSON.stringify({ passed: results.length, results, scope: 'Real browser with mocked media/RTC/signaling; no physical-device call' }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
