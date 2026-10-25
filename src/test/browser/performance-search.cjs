const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async () => {
  const phase = process.argv[2] || 'after';
  const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
  const browser = await chromium.launch({executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true});
  try {
    const page = await browser.newPage();
    let oldStarted; const started = new Promise(resolve => oldStarted = resolve);
    let releaseOld; const release = new Promise(resolve => releaseOld = resolve);
    let aborted = 0; let searchRequests = 0; let revoked = false; let videoRequests = 0;
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('requestfailed', request => { if (request.url().includes('/api/search/users')) aborted++; });
    await page.route('http://aloute.test/**', async route => {
      const path = new URL(route.request().url());
      if (path.pathname === '/js/app.js') return route.fulfill({contentType:'application/javascript; charset=utf-8', body:source});
      if (path.pathname === '/') return route.fulfill({contentType:'text/html; charset=utf-8', body:'<!doctype html><html><head><meta charset="utf-8"><title>Search performance fixture</title></head><body><input id="searchInput"><button id="searchClear">Clear</button><div id="searchTabs"><button class="search-tab" data-tab="users">Users</button><button class="search-tab" data-tab="posts">Posts</button></div><div id="searchResults"></div><div id="searchDiscover"></div><script src="/js/app.js"></script></body></html>'});
      if (path.pathname === '/video.mp4') { videoRequests++; return route.fulfill({contentType:'video/mp4',body:''}); }
      let data = [];
      if (path.pathname === '/api/search/users') {
        searchRequests++;
        const q = path.searchParams.get('q');
        if (q === 'old') { oldStarted(); await release; }
        data = revoked ? [] : [{id:1, username:q, fullName:'User '+q, avatarUrl:'/images/avatar_default.png'}];
      }
      if (path.pathname === '/api/search/posts') data = [{id:'video-post',userId:1,username:'author',content:'Video card',videoUrl:'http://aloute.test/video.mp4',reactionCounts:{}}];
      try { await route.fulfill({contentType:'application/json', body:JSON.stringify({success:true,data})}); }
      catch (error) { if (!route.request().failure()) throw error; }
    });
    await page.goto('http://aloute.test/');
    // The selector follows the real search template contract.
    const input = page.locator('#searchInput');
    await input.fill('old'); await started;
    await input.fill('new'); releaseOld();
    await page.waitForTimeout(150);
    const staleWhileDebouncing = (await page.locator('#searchResults').textContent()).includes('User old');
    await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('User new'));
    const result = {phase, searchRequests, aborted, staleWhileDebouncing, errors};
    if (phase === 'after') {
      assert.equal(staleWhileDebouncing, false);
      assert.equal(aborted, 1);
      assert.deepEqual(errors, []);
      await input.fill('');
      assert.equal(await page.locator('#searchResults').isVisible(), false);
      revoked = true;
      await input.fill('new');
      await page.waitForFunction(() => document.querySelector('#searchResults').textContent.includes('Không tìm thấy ai'));
      assert.equal(searchRequests, 3);
      result.revalidatedSameQuery = true;
      revoked = false;
      for (const term of [...Array.from({length:21},(_,i)=>'word'+i),'0']) {
        await input.fill(term); await input.press('Enter');
        await page.waitForFunction(value => document.querySelector('#searchResults').textContent.includes('User '+value),term);
      }
      assert.deepEqual(errors,[]);
      result.numericQueryAfterCacheLimit = true;
      await page.locator('[data-tab="posts"]').click();
      await page.waitForSelector('video.post__video');
      assert.equal(await page.locator('video').evaluate(video => video.preload), 'none');
      assert.equal(await page.locator('video').evaluate(video => video.controls), true);
      await page.waitForTimeout(100);
      assert.equal(videoRequests, 0);
      result.dynamicVideoPreload = 'none';
    }
    console.log(JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
