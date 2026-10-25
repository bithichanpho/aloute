const assert = require('node:assert/strict');
const {chromium} = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async () => {
  const base = process.argv[2];
  const browser = await chromium.launch({executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true});
  try {
    // CONNECTED is emitted before the broker has necessarily processed SUBSCRIBE.
    // Establish actual receive readiness with fixture-only probes before the event under test.
    async function receiveReady(pages, probe) {
      const deadline = Date.now() + 10000;
      while (Date.now() < deadline) {
        await pages[0].request.get(base+'/smoke/send?to=one&id='+encodeURIComponent(probe));
        try {
          await Promise.all(pages.map(page => page.waitForFunction(id => window.calls.includes(id), probe, {timeout:400})));
          return;
        } catch (error) {
          if (error.name !== 'TimeoutError') throw error;
        }
      }
      assert.fail('Fixture call subscriptions did not become ready');
    }
    const a = await browser.newContext(); const b = await browser.newContext();
    const one = await a.newPage(); const two = await b.newPage();
    for (const page of [one, two]) await page.addInitScript(() => {
      window.policyViolations = []; window.calls = [];
      document.addEventListener('securitypolicyviolation', e => window.policyViolations.push(e.violatedDirective));
      document.addEventListener('aloute:call-signal', e => window.calls.push(e.detail.callId));
    });
    await Promise.all([one.goto(base+'/smoke?as=one'), two.goto(base+'/smoke?as=two')]);
    await Promise.all([one.waitForFunction(() => window.__alouteRealtimeConnected === true),
      two.waitForFunction(() => window.__alouteRealtimeConnected === true)]);
    assert.deepEqual(await one.evaluate(() => window.policyViolations), []);
    assert.equal(await one.evaluate(() => typeof SockJS), 'function');
    assert.equal(await one.evaluate(() => typeof Stomp.over), 'function');
    const transport = await one.evaluate(() => new Promise(resolve => Aloute.onRealtimeReady(client => resolve(client.ws._transport.transportName))));
    assert.equal(transport, 'websocket');
    await receiveReady([one], 'ready-initial');
    await one.request.get(base+'/smoke/send?to=one&id=first');
    await one.waitForFunction(() => window.calls.includes('first'));
    assert.deepEqual(await two.evaluate(() => window.calls), []);
    await one.evaluate(() => Aloute.onRealtimeReady(client => client.ws.close()));
    await one.waitForFunction(() => window.__alouteRealtimeConnected === false);
    await one.waitForFunction(() => window.__alouteRealtimeConnected === true);
    await receiveReady([one], 'ready-reconnect');
    await one.request.get(base+'/smoke/send?to=one&id=reconnected');
    await one.waitForFunction(() => window.calls.includes('reconnected'));
    assert.deepEqual(await two.evaluate(() => window.calls), []);
    const tab = await a.newPage();
    await tab.addInitScript(() => { window.calls=[]; document.addEventListener('aloute:call-signal', e => window.calls.push(e.detail.callId)); });
    await tab.goto(base+'/smoke?as=one'); await tab.waitForFunction(() => window.__alouteRealtimeConnected === true);
    await receiveReady([one, tab], 'ready-multi-tab');
    await one.request.get(base+'/smoke/send?to=one&id=multi-tab');
    await Promise.all([one.waitForFunction(() => window.calls.includes('multi-tab')), tab.waitForFunction(() => window.calls.includes('multi-tab'))]);
    for (const page of [one,tab]) assert.equal(await page.evaluate(() => window.calls.filter(id => id === 'multi-tab').length),1);
    assert.deepEqual(await two.evaluate(() => window.calls), []);
    assert.deepEqual(await one.evaluate(() => window.policyViolations), []);
    const anonymous = await browser.newContext(); const denied = await anonymous.newPage();
    await denied.goto(base+'/anonymous');
    await denied.waitForTimeout(1800);
    assert.notEqual(await denied.evaluate(() => window.__alouteRealtimeConnected), true);
    console.log('PASS: local pinned libraries; default CSP; authenticated STOMP; reconnect; multi-tab; cross-account isolation; anonymous rejection.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
