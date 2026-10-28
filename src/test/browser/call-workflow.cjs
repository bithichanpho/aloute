const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Full production chat.js in a real browser; media, peer transport and STOMP are test doubles.
const source = fs.readFileSync('src/main/resources/static/js/chat.js', 'utf8');
const callsSource = fs.readFileSync('src/main/resources/static/js/calls.js', 'utf8');
const template = fs.readFileSync('src/main/resources/templates/chat/index.html', 'utf8')
  .match(/<template id="chatThreadTemplate">[\s\S]*?<\/template>/)[0];
const html = `<body data-current-user-id="1"><div id="chatList"><button class="chat-item" data-user-id="2"
 data-username="friend.name" data-fullname="Friend"><span class="chat-item__preview"></span><span class="chat-item__time"></span></button></div>
 <div id="chatThread"></div><div id="chatDetails"><a id="chatDetailsProfileLink"></a></div>${template}
 <div id="videoCallModal" hidden><h2 id="videoCallTitle"></h2><p id="videoCallStatus"></p>
 <video id="localVideo"></video><video id="remoteVideo"></video><button id="answerCallBtn">Answer</button>
 <button id="rejectCallBtn">Reject</button><button id="hangupCallBtn">Hang up</button></div>`;

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const results = [];
  try {
    async function fixture() {
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: html }));
      await page.goto('https://aloute.test/chat');
      await page.clock.install();
      await page.evaluate(() => {
        window.framesSent = []; window.toasts = []; window.peers = []; window.stoppedTracks = 0;
        window.__alouteRealtimeConnected = true;
        window.fixtureClient = { send(destination, headers, body) { framesSent.push({ destination, ...JSON.parse(body) }); } };
        window.Aloute = {
          onRealtimeReady(fn) {
            (window.realtimeListeners ||= []).push(fn);
            window.reconnectFixture = () => realtimeListeners.forEach(listener => listener(fixtureClient));
            fn(fixtureClient);
          },
          toast: (...args) => toasts.push(args),
          escapeHtml: value => String(value ?? '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch])),
          api: async url => url.includes('ice-servers') ? [{ urls: 'stun:stun.example.invalid' }] : []
        };
        navigator.mediaDevices.getUserMedia = async () => {
          if (window.delayMedia) await new Promise(resolve => { window.releaseMedia = resolve; });
          const stream = new MediaStream();
          stream.getTracks = () => [{ stop() { stoppedTracks++; } }];
          return stream;
        };
        window.RTCPeerConnection = class {
          constructor() { this.connectionState = 'new'; this.candidates = []; peers.push(this); }
          addTrack() {}
          async createOffer() { return { type: 'offer', sdp: 'test-offer' }; }
          async createAnswer() { return { type: 'answer', sdp: 'test-answer' }; }
          async setLocalDescription(value) {
            this.localDescription = value;
            this.onicecandidate?.({ candidate: { candidate: 'early-local' } });
          }
          async setRemoteDescription(value) { this.remoteDescription = value; }
          async addIceCandidate(value) { this.candidates.push(value); }
          close() { this.connectionState = 'closed'; }
        };
      });
      await page.addScriptTag({ content: callsSource });
      await page.addScriptTag({ content: source });
      await page.locator('.chat-item').click();
      assert.equal(await page.locator('#chatDetailsProfileLink').getAttribute('href'), '/people/friend.name');
      return { page, errors };
    }
    async function signal(page, detail) {
      await page.evaluate(value => document.dispatchEvent(new CustomEvent('aloute:call-signal', { detail: value })), detail);
    }
    {
      const { page, errors } = await fixture();
      await page.locator('#audioCallBtn').click();
      await page.waitForFunction(() => framesSent.some(f => f.destination === '/app/call.offer'));
      const callId = await page.evaluate(() => framesSent[0].callId);
      assert.deepEqual(await page.evaluate(() => framesSent.map(f => f.destination)), ['/app/call.offer', '/app/call.ice']);
      await signal(page, { type: 'ice', callId, candidate: { candidate: 'early-remote' } });
      assert.equal(await page.evaluate(() => peers[0].candidates.length), 0);
      await signal(page, { type: 'answer', callId, sdp: { type: 'answer', sdp: 'fixture' } });
      await page.waitForFunction(() => peers[0].candidates.length === 1);
      await signal(page, { type: 'hangup', callId: 'old-call' });
      assert.equal(await page.locator('#videoCallModal').isVisible(), true);
      await page.evaluate(() => { peers[0].connectionState = 'disconnected'; peers[0].onconnectionstatechange(); });
      await page.clock.runFor(5000);
      await page.evaluate(() => { peers[0].connectionState = 'connected'; peers[0].onconnectionstatechange(); });
      await page.clock.runFor(10001);
      assert.equal(await page.locator('#videoCallModal').isVisible(), true);
      assert.equal(await page.evaluate(() => framesSent.filter(f => f.destination === '/app/call.hangup').length), 0);
      await page.evaluate(() => { peers[0].connectionState = 'failed'; peers[0].onconnectionstatechange(); });
      await page.clock.runFor(5001);
      assert.equal(await page.locator('#videoCallModal').isVisible(), false);
      assert.equal(await page.evaluate(() => framesSent.filter(f => f.destination === '/app/call.hangup').length), 1);
      assert.deepEqual(errors, []); results.push('outgoing ICE order, stale hangup and failed-connection hangup'); await page.close();
    }
    {
      const { page, errors } = await fixture();
      const offer = { type: 'offer', callId: 'incoming', fromUserId: 2, callType: 'audio', sdp: { type: 'offer', sdp: 'fixture' } };
      await signal(page, offer);
      await signal(page, { type: 'ice', callId: 'incoming', candidate: { candidate: 'before-accept' } });
      await page.locator('#answerCallBtn').click();
      await page.waitForFunction(() => framesSent.some(f => f.destination === '/app/call.answer'));
      assert.equal(await page.evaluate(() => peers[0].candidates.length), 1);
      assert.deepEqual(await page.evaluate(() => framesSent.map(f => f.destination)), ['/app/call.answer', '/app/call.ice']);
      await signal(page, { ...offer, callId: 'overlapping' });
      assert.equal(await page.evaluate(() => framesSent.at(-1).callId), 'overlapping');
      assert.equal(await page.locator('#videoCallModal').isVisible(), true);
      await page.evaluate(() => { window.__alouteRealtimeConnected = false; });
      await page.locator('#hangupCallBtn').click();
      const before = await page.evaluate(() => framesSent.length);
      await page.evaluate(() => { window.__alouteRealtimeConnected = true; reconnectFixture(); });
      assert.equal(await page.evaluate(() => framesSent.length), before + 1);
      assert.equal(await page.evaluate(() => framesSent.at(-1).callId), 'incoming');
      assert.deepEqual(errors, []); results.push('incoming ICE, overlapping offer, disconnected hangup replay'); await page.close();
    }
    {
      const { page, errors } = await fixture();
      await page.evaluate(() => { window.delayMedia = true; });
      await page.locator('#audioCallBtn').click();
      await page.waitForFunction(() => !!window.releaseMedia);
      await page.locator('#hangupCallBtn').click();
      await page.evaluate(() => releaseMedia());
      await page.waitForFunction(() => stoppedTracks === 1);
      assert.equal(await page.evaluate(() => peers.length), 0);
      assert.equal(await page.evaluate(() => framesSent.filter(f => f.destination === '/app/call.offer').length), 0);
      assert.deepEqual(errors, []); results.push('cancel during device permission does not resurrect call'); await page.close();
    }
    {
      const { page, errors } = await fixture();
      await page.locator('#audioCallBtn').click();
      await page.waitForFunction(() => framesSent.some(f => f.destination === '/app/call.offer'));
      const oldId = await page.evaluate(() => framesSent[0].callId);
      await page.evaluate(() => { peers[0].setRemoteDescription = () => new Promise((_, reject) => { window.rejectOldAnswer = reject; }); });
      await signal(page, { type: 'answer', callId: oldId, sdp: { type: 'answer', sdp: 'fixture' } });
      await page.waitForFunction(() => !!window.rejectOldAnswer);
      await page.locator('#hangupCallBtn').click();
      await page.clock.runFor(1);
      await page.locator('#audioCallBtn').click();
      await page.waitForFunction(() => peers.length === 2 && framesSent.filter(f => f.destination === '/app/call.offer').length === 2);
      await page.evaluate(() => rejectOldAnswer(new Error('Delayed old answer failure')));
      assert.equal(await page.locator('#videoCallModal').isVisible(), true);
      assert.equal(await page.evaluate(() => peers[1].connectionState), 'new');
      assert.deepEqual(errors, []); results.push('stale async answer failure cannot close replacement call'); await page.close();
    }
    {
      const { page, errors } = await fixture();
      for (const [status, duration, text] of [['ENDED', 87, '1 phút 27 giây'], ['DECLINED', 0, 'Bị từ chối'], ['CANCELLED', 0, 'Đã hủy'], ['MISSED', 0, 'Không được trả lời']]) {
        await page.evaluate(msg => document.dispatchEvent(new CustomEvent('aloute:message', { detail: msg })), {
          id: 'history-' + status, senderId: '1', receiverId: '2', messageType: 'CALL', callType: 'audio', callStatus: status,
          callDurationSeconds: duration, sentAt: '2026-10-28T01:00:00', content: ''
        });
        assert.ok((await page.locator('#messageList').innerText()).includes(text));
      }
      assert.deepEqual(errors, []); results.push('four terminal call cards and server duration'); await page.close();
    }
    console.log(JSON.stringify({ passed: results.length, results, scope: 'Browser with mocked media/RTC/STOMP; no physical-device or TURN test' }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
