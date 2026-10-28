const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
let source = fs.readFileSync('src/main/resources/static/js/app.js');
const article = id => `<article class="post" data-post-id="${id}">${id}</article>`;
const shell = content => `<!doctype html><html><head><meta charset="utf-8"><style>
  [hidden]{display:none!important}body{margin:0}.post{height:1600px;border-bottom:1px solid;box-sizing:border-box}
  button{min-height:44px}nav{position:fixed;top:0;right:0;z-index:20}
  </style></head><body>${content}<script src="/js/app.js"></script></body></html>`;
const loader = (cursor = 'c1') => `<button data-load-posts data-url="/api/posts/feed-fragment?tab=following&tag=hello" data-cursor="${cursor}">Tải thêm</button>`;
const fragment = (route, body, next = '', more = false) => route.fulfill({contentType:'text/html',
  headers:{'X-Next-Cursor':next,'X-Has-More':String(more)},body});
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

(async () => {
  if (process.env.ALOUTE_INFINITE_ASSET_ORIGIN) {
    const response = await fetch(process.env.ALOUTE_INFINITE_ASSET_ORIGIN + '/js/app.js?v=20261028-infinite-posts-v2');
    assert.equal(response.status,200);
    const served = Buffer.from(await response.arrayBuffer());
    assert.deepEqual(served,source,'The running app must serve the updated script');
    source = served;
  }
  const browser = await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  async function open(width, html, respond) {
    const page = await browser.newPage({viewport:{width,height:900}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== 'http://aloute.test') return route.fulfill({status:204,body:''});
      if (url.pathname === '/js/app.js') return route.fulfill({contentType:'text/javascript',body:source});
      if (url.pathname.startsWith('/api/posts/')) return respond(route,url);
      if (url.pathname.startsWith('/api/')) return route.fulfill({contentType:'application/json',body:'{"success":true,"data":{"count":0}}'});
      return route.fulfill({contentType:'text/html',body:typeof html === 'function' ? html(url) : html});
    });
    await page.goto('http://aloute.test/feed');
    return {page,errors};
  }
  const bottom = page => page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  try {
    for (const width of [1280,390]) {
      // A slow response starts with reading space left and finishes before reaching the end.
      let prefetched = 0;
      const early = await open(width,shell(`<main>${article('reading0')}${article('reading1')}${loader()}</main>`),async route => {
        prefetched++;
        await delay(350);
        return fragment(route,article('ready-before-bottom'));
      });
      await early.page.evaluate(() => window.scrollTo(0,700));
      await early.page.waitForFunction(() => document.querySelector('[data-load-posts]').disabled);
      const readingSpace = await early.page.evaluate(() => document.documentElement.scrollHeight - scrollY - innerHeight);
      assert.ok(readingSpace >= 1200,'Start fetching while more than one screen of posts remains');
      await early.page.locator('[data-post-id="ready-before-bottom"]').waitFor();
      assert.equal(prefetched,1);
      assert.equal(await early.page.evaluate(() => scrollY),700,'Background loading must not move the reading position');
      assert.deepEqual(early.errors,[]);
      await early.page.close();

      // Multiple pages, duplicate suppression, filtered empty windows, one request at a time, final stop.
      const requests = [];
      let active = 0, maxActive = 0;
      const {page,errors} = await open(width,shell(`<main>${article('a0')}${article('a1')}${loader()}</main>`),async (route,url) => {
        assert.equal(url.searchParams.get('tab'),'following');
        assert.equal(url.searchParams.get('tag'),'hello');
        requests.push(url.searchParams.get('cursor'));
        maxActive = Math.max(maxActive, ++active);
        await delay(80);
        const cursor = url.searchParams.get('cursor');
        if (cursor === 'c1') await fragment(route,article('a1')+article('a2')+article('a3'),'c2',true);
        else if (cursor === 'c2') await fragment(route,'','c3',true);
        else { assert.equal(cursor,'c3'); await fragment(route,article('a4')); }
        active--;
      });
      await page.waitForTimeout(150);
      assert.equal(requests.length,0,'Do not load distant pages before scrolling');
      await bottom(page);
      await page.waitForFunction(() => document.querySelector('[data-load-posts]').disabled);
      await page.locator('[data-load-posts]').dispatchEvent('click');
      await page.locator('[data-post-id="a3"]').waitFor();
      await bottom(page);
      await page.locator('[data-post-id="a4"]').waitFor();
      assert.deepEqual(requests,['c1','c2','c3']);
      assert.equal(maxActive,1);
      assert.equal(await page.locator('.post').count(),5);
      assert.equal(await page.locator('[data-posts-end]').isVisible(),true);
      assert.equal(await page.locator('[data-load-posts]').isVisible(),false);
      await bottom(page); await page.waitForTimeout(150);
      assert.equal(requests.length,3);
      assert.deepEqual(errors,[]);
      await page.close();

      // A failed page preserves existing posts, waits for an explicit retry, then resumes scrolling.
      let attempts = 0;
      const retry = await open(width,shell(`<main>${article('kept0')}${article('kept1')}${loader()}</main>`),async (route,url) => {
        attempts++;
        if (attempts === 1) return route.fulfill({status:503,body:'Unavailable'});
        if (url.searchParams.get('cursor') === 'c1') return fragment(route,article('recovered'),'c2',true);
        assert.equal(url.searchParams.get('cursor'),'c2');
        return fragment(route,article('last'));
      });
      await bottom(retry.page);
      await retry.page.getByRole('button',{name:'Thử tải lại',exact:true}).waitFor();
      assert.equal(await retry.page.locator('.post').count(),2);
      await bottom(retry.page); await retry.page.waitForTimeout(150);
      assert.equal(attempts,1);
      await retry.page.getByRole('button',{name:'Thử tải lại',exact:true}).click();
      await retry.page.locator('[data-post-id="recovered"]').waitFor();
      await bottom(retry.page);
      await retry.page.locator('[data-post-id="last"]').waitFor();
      assert.equal(attempts,3);
      assert.deepEqual(retry.errors,[]);
      await retry.page.close();

      // A broken cursor cannot trigger an endless request loop or append duplicate posts.
      let stuckCalls = 0;
      const stuck = await open(width,shell(`<main>${article('kept')}${loader()}</main>`),async route => {
        stuckCalls++; return fragment(route,article('duplicate'),'c1',true);
      });
      await stuck.page.getByRole('button',{name:'Thử tải lại',exact:true}).waitFor();
      await stuck.page.waitForTimeout(150);
      assert.equal(stuckCalls,1);
      assert.equal(await stuck.page.locator('.post').count(),1);
      assert.deepEqual(stuck.errors,[]);
      await stuck.page.close();

      // A media grid drains text-only windows, adds video tiles, and shares the timeline cursor.
      const profileCalls = [];
      const profile = await open(width,shell(`<nav>
        <button data-profile-view="list">Dòng thời gian</button><button data-profile-view="grid">Lưới</button>
        <button data-profile-view="reposts">Đăng lại</button></nav>
        <div id="profilePosts">${article('text-first')}${article('text-second')}${loader()}</div>
        <div id="profileGridWrap" hidden><p data-profile-media-empty>Chưa có ảnh</p><div id="profileGrid"></div></div>
        <div id="profileRepostsWrap" hidden><p data-profile-reposts-empty hidden>Chưa đăng lại</p>
        <button data-load-posts data-profile-reposts-loader data-profile-initial="1" data-url="/api/posts/user/1/reposts/fragment">Tải đăng lại</button></div>`),async (route,url) => {
        profileCalls.push(url.pathname + '?' + url.searchParams.get('cursor'));
        if (url.pathname.endsWith('/reposts/fragment')) return fragment(route,article('reposted'));
        const cursor = url.searchParams.get('cursor');
        if (cursor === 'c1') return fragment(route,article('text-only'),'c2',true);
        assert.equal(cursor,'c2');
        return fragment(route,'<article class="post" data-post-id="own-video"><video class="post__video"></video></article>');
      });
      await profile.page.getByRole('button',{name:'Lưới',exact:true}).click();
      await profile.page.locator('#profileGrid [data-goto-post="own-video"]').waitFor();
      assert.equal(await profile.page.locator('#profileGrid video').count(),1);
      assert.equal(await profile.page.locator('[data-profile-media-empty]').isVisible(),false);
      assert.equal(profileCalls.length,2,'Hidden reposts must not load');
      assert.equal(await profile.page.locator('#profileGridWrap [data-posts-end]').isVisible(),true);
      await profile.page.getByRole('button',{name:'Dòng thời gian',exact:true}).click();
      assert.equal(await profile.page.locator('#profilePosts .post').count(),4);
      assert.equal(await profile.page.locator('#profilePosts [data-posts-end]').isVisible(),true);
      await profile.page.getByRole('button',{name:'Đăng lại',exact:true}).click();
      await profile.page.locator('#profileRepostsWrap [data-post-id="reposted"]').waitFor();
      assert.equal(await profile.page.locator('#profileGrid .grid-tile').count(),1);
      assert.deepEqual(profile.errors,[]);
      await profile.page.close();

      // Leaving a page cancels its in-flight load and disconnects its observer.
      let release;
      const pending = new Promise(resolve => { release = resolve; });
      const navigation = await open(width,url => url.pathname === '/search' ? shell('<main>Trang tìm kiếm mới</main>')
        : shell(`<nav><a class="sidebar__row" href="/search">Tìm kiếm</a></nav>
        <main>${article('old')}${loader()}</main>`),async route => {
        await pending;
        try { await fragment(route,article('late'),'c2',true); } catch (_) { /* Navigation aborted this request. */ }
      });
      await navigation.page.waitForFunction(() => document.querySelector('[data-load-posts]').disabled);
      await navigation.page.getByRole('link',{name:'Tìm kiếm',exact:true}).click();
      await navigation.page.waitForURL('**/search');
      release();
      await navigation.page.waitForTimeout(150);
      assert.equal(await navigation.page.locator('[data-post-id="late"]').count(),0);
      assert.deepEqual(navigation.errors,[]);
      await navigation.page.close();

      let deepPages = 0;
      const deep = await open(width,shell(`<style>.post{height:1px}</style><main>${loader()}</main>`),async (route,url) => {
        deepPages++;
        assert.equal(url.searchParams.get('cursor'),'c' + deepPages);
        return fragment(route,Array.from({length:20},(_,i) => article('deep-' + ((deepPages-1)*20+i))).join(''),
          deepPages < 14 ? 'c' + (deepPages+1) : '',deepPages < 14);
      });
      await deep.page.locator('[data-posts-end]').waitFor({state:'visible'});
      assert.equal(await deep.page.locator('.post').count(),280,'Keep loading beyond 200 posts until the server reports exhaustion');
      assert.equal(deepPages,14);
      assert.deepEqual(deep.errors,[]);
      await deep.page.close();
      console.log(JSON.stringify({width,passed:true,cursors:requests,retryResumes:true,exhaustionStops:true}));
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
