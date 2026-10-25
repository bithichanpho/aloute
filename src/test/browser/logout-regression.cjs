const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async () => {
  const before = process.argv.includes('before');
  const source = fs.readFileSync(process.env.ALOUTE_LOGOUT_SOURCE || 'src/main/resources/static/js/app.js', 'utf8');
  const browser = await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  const results = [];
  try {
    const scenarios = before ? ['denied','push-hangs','success','refresh-race','logout-timeout']
      : ['denied','network-error','storage-blocked','push-hangs','success','refresh-race','refresh-hangs','logout-timeout'];
    for (const scenario of scenarios) {
      const context = await browser.newContext();
      const page = await context.newPage();
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let logout = 0, refresh = 0, csrf = false, releaseRefresh;
      let deny = scenario === 'denied';
      const pendingRefresh = new Promise(resolve => releaseRefresh = resolve);
      await context.addCookies([{name:'XSRF-TOKEN',value:'test-csrf',url:'http://aloute.test'}]);
      await page.route('http://aloute.test/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/js/app.js') return route.fulfill({contentType:'application/javascript; charset=utf-8',body:source});
        if (path === '/login') return route.fulfill({contentType:'text/html',body:'<p>Login fixture</p>'});
        if (path === '/') return route.fulfill({contentType:'text/html; charset=utf-8',body:'<!doctype html><html><body><a id="logoutBtn" href="/logout">Logout</a><a data-logout href="/logout">Mobile</a><script src="/js/app.js"></script></body></html>'});
        if (path === '/api/push/register') return; // Simulate a fetch that never completes.
        if (path === '/api/auth/refresh') {
          refresh++; await pendingRefresh;
          return route.fulfill({status:200,contentType:'application/json',body:'{"success":true}'});
        }
        if (path === '/api/test-unauthorized') return route.fulfill({status:401,contentType:'application/json',body:'{}'});
        if (path === '/api/auth/logout') {
          logout++; csrf = route.request().headers()['x-xsrf-token'] === 'test-csrf';
          if (scenario === 'network-error') return route.abort('connectionrefused');
          if (scenario === 'logout-timeout') return;
          await new Promise(resolve => setTimeout(resolve,100));
          return route.fulfill({status:deny?403:200,contentType:'application/json',body:JSON.stringify({success:!deny,message:'Test logout rejected'})});
        }
        return route.fulfill({contentType:'application/json',body:'{"success":true,"data":[]}'});
      });
      if (scenario === 'storage-blocked') await page.addInitScript(() => {
        for (const method of ['getItem','setItem','removeItem']) Storage.prototype[method] = () => { throw new Error('Storage blocked'); };
      });
      await page.goto('http://aloute.test/');
      if (scenario === 'push-hangs') await page.evaluate(() => localStorage.setItem('aloute-fcm-fid','synthetic-installation'));
      if (scenario === 'refresh-race' || scenario === 'refresh-hangs') {
        await page.evaluate(() => { window.pendingApi = Aloute.api('/api/test-unauthorized').catch(()=>{}); });
        await page.waitForFunction(()=>true);
        for(let i=0;i<50 && refresh===0;i++) await page.waitForTimeout(10);
        assert.equal(refresh,1);
      }
      if (scenario === 'logout-timeout' || scenario === 'refresh-hangs') await page.clock.install();
      await page.evaluate(({doubleClick, testRefresh}) => {
        document.querySelector('#logoutBtn').click();
        if(doubleClick) {
          document.querySelector('[data-logout]').click();
          if(testRefresh) window.refreshDuringLogout = Aloute.api('/api/test-unauthorized').catch(()=>{});
        }
      },{doubleClick:scenario === 'success',testRefresh:!before});
      if (scenario === 'refresh-race') {
        await page.waitForTimeout(150);
        if (!before) assert.equal(logout,0,'Logout must wait for an already running rotation');
        releaseRefresh();
      }
      if (scenario === 'refresh-hangs' || scenario === 'logout-timeout') await page.clock.fastForward(13000);
      if (scenario === 'refresh-hangs') {
        releaseRefresh(); // A late refresh must not resume the timed out logout operation.
      }
      await page.waitForTimeout(scenario === 'push-hangs'?2600:350);
      const stayed = new URL(page.url()).pathname === '/';
      const toast = await page.evaluate(()=>document.getElementById('toastHost')?.textContent || '');
      const result = {scenario,logout,refresh,csrf,stayed,visibleError:!!toast,errors};
      results.push(result);
      if (!before) {
        assert.deepEqual(errors,[]);
        assert.equal(logout,scenario === 'refresh-hangs'?0:1);
        if (scenario === 'success') assert.equal(refresh,0,'Unauthorized background requests must not rotate a session during logout');
        if (scenario !== 'refresh-hangs') assert.equal(csrf,true);
        if (scenario === 'denied' || scenario === 'network-error' || scenario === 'logout-timeout' || scenario === 'refresh-hangs') {
          assert.equal(stayed,true,'A failed logout must not pretend the session ended');
          assert.ok(toast,'Logout errors must be visible');
          assert.equal(await page.locator('#logoutBtn').getAttribute('aria-busy'),null);
          if (scenario === 'denied') {
            deny=false; await page.locator('[data-logout]').click();
            await page.waitForURL('**/login'); assert.equal(logout,2);
          }
        } else assert.equal(stayed,false,'Successful logout must reach login despite stalled push cleanup');
      }
      await context.close();
    }
    console.log(JSON.stringify({before,results}));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
