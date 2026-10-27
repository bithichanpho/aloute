const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const profile = fs.readFileSync('src/main/resources/templates/profile/view.html', 'utf8');
  const modal = profile.slice(profile.indexOf('<div class="people-modal"'), profile.lastIndexOf('</section>'));
  const layout = fs.readFileSync('src/main/resources/templates/layout/base.html', 'utf8');
  const confirm = layout.slice(layout.indexOf('<div class="modal fade" id="alouteConfirmModal"'), layout.indexOf('<!-- Modal nhập liệu'));
  const reply = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  try {
    for (const config of [
      { width: 1280, theme: 'dark', owner: true }, { width: 390, theme: 'light', owner: true, omitOwnFlag: true },
      { width: 390, theme: 'dark', owner: false }, { width: 1280, theme: 'light', owner: false, anonymous: true }
    ]) {
      const page = await browser.newPage({ viewport: { width: config.width, height: 850 } }); page.setDefaultTimeout(7000);
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let followers = Array.from({ length: 31 }, (_, i) => ({ id: i + 2, username: 'user' + (i + 2), fullName: 'Người theo dõi ' + (i + 2), followedByCurrentUser: true, mutualFriend: true }));
      let requests = [], writes = 0, fail = true;
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf', url: 'http://aloute.test' }]);
      await page.route('http://aloute.test/**', async route => {
        const url = new URL(route.request().url()), path = url.pathname;
        if (path === '/api/users/1/followers') {
          const offset = Number(url.searchParams.get('page') || 0) * 30; requests.push(offset);
          return reply(route, followers.slice(offset, offset + 30));
        }
        if (/^\/api\/users\/1\/followers\/\d+$/.test(path)) {
          assert.equal(route.request().method(), 'DELETE');
          assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf'); writes++;
          if (fail) { fail = false; return route.fulfill({ status: 503, contentType: 'application/json', body: '{"success":false,"message":"Thử lại sau"}' }); }
          followers = followers.filter(user => String(user.id) !== path.split('/').pop());
          return reply(route, { removed: true, followerCount: followers.length });
        }
        if (path === '/api/users/1/following') return reply(route, [{ id: 2, username: 'user2', fullName: 'Người theo dõi 2', followedByCurrentUser: true }]);
        if (path.startsWith('/api/')) return reply(route, []);
        if (['/js/app.js', '/js/ui.js', '/vendor/bootstrap/bootstrap.bundle.min.js', '/css/main.css', '/css/theme.css', '/vendor/bootstrap/bootstrap-lite.min.css'].includes(path))
          return route.fulfill({ path: 'src/main/resources/static' + path });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body: '' });
        if (path.endsWith('.png')) return route.fulfill({ path: 'src/main/resources/static/images/avatar_default.png' });
        const stats = `<div class="profile-stats" data-profile-user-id="1" ${config.omitOwnFlag ? '' : `data-own-profile="${config.owner}"`}><button data-people-tab="followers"><strong>31</strong> người theo dõi</button><button data-people-tab="following"><strong>1</strong> đang theo dõi</button><button data-people-tab="close"><strong>0</strong> bạn thân</button></div>`;
        return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="vi" data-bs-theme="${config.theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/vendor/bootstrap/bootstrap-lite.min.css"><link rel="stylesheet" href="/css/main.css"><link rel="stylesheet" href="/css/theme.css"></head><body data-current-user-id="${config.anonymous ? '' : config.owner ? '1' : '9'}"><div id="toastHost"></div><main style="padding:24px">${stats}</main>${modal}${confirm}<script src="/vendor/bootstrap/bootstrap.bundle.min.js"></script><script src="/js/ui.js"></script><script src="/js/app.js"></script></body></html>` });
      });
      await page.goto('http://aloute.test/profile/1');
      await page.locator('[data-people-tab="followers"]').click();
      await page.locator('#peopleModalBody .discover-user').first().waitFor();
      if (!config.owner) {
        assert.equal(await page.locator('[data-remove-follower]').count(), 0);
      } else {
        const remove = () => page.locator('[data-remove-follower="2"]');
        assert.equal(await remove().locator('svg').count(), 1);
        assert.equal(await remove().getAttribute('title'), 'Xóa người theo dõi');
        assert.match(await remove().getAttribute('aria-label'), /Xóa @user2/);
        assert.equal(await remove().evaluate(el => el.closest('.discover-user__actions') === null), true);
        const row = remove().locator('..');
        const nameBox = await row.locator('.discover-user__main').boundingBox();
        const removeBox = await remove().boundingBox();
        assert.ok(removeBox.x >= nameBox.x + nameBox.width);
        assert.ok(removeBox.x + removeBox.width <= config.width);
        await page.locator('[data-more-followers]').click();
        await page.locator('[data-remove-follower="32"]').waitFor();
        assert.deepEqual(requests, [0, 30]);
        assert.equal(await page.locator('[data-remove-follower]').count(), 31);
        await page.locator('#peopleModalBody').evaluate(el => { el.scrollTop = 0; });
        await page.screenshot({ path: `target/remove-follower-${config.width}-${config.theme}.png` });

        await remove().click(); await page.locator('#alouteConfirmModal.show').waitFor();
        await page.locator('#alouteConfirmCancel').click();
        await page.waitForFunction(() => !document.querySelector('[data-remove-follower="2"]').disabled);
        assert.equal(writes, 0);

        await remove().click(); await page.locator('#alouteConfirmModal.show').waitFor();
        // Duplicate attempts while confirmation is open must not send a second write.
        await page.evaluate(() => document.querySelector('[data-remove-follower="2"]').click());
        await page.locator('#alouteConfirmOk').click();
        await page.waitForFunction(() => document.body.textContent.includes('Thử lại sau'));
        assert.equal(writes, 1); assert.equal(await remove().isEnabled(), true);
        assert.equal(await page.locator('[data-people-tab="followers"] strong').innerText(), '31');

        await remove().click(); await page.locator('#alouteConfirmModal.show').waitFor();
        await page.locator('#alouteConfirmOk').click();
        await remove().waitFor({ state: 'detached' });
        await page.locator('[data-remove-follower="32"]').waitFor();
        assert.equal(writes, 2);
        assert.equal(await page.locator('[data-people-tab="followers"] strong').innerText(), '30');
        assert.equal(await page.locator('#peopleModal [data-tab="followers"] span').innerText(), '30');
        assert.equal(await page.locator('[data-people-tab="following"] strong').innerText(), '1');
        assert.deepEqual(requests, [0, 30, 0]);
        await page.locator('#peopleModal [data-tab="following"]').click();
        await page.locator('#peopleModalBody .btn-follow.is-following').waitFor();
        assert.equal(await page.locator('[data-remove-follower]').count(), 0);
        await page.locator('#peopleModal [data-tab="followers"]').click();
        assert.equal(await remove().count(), 0);
        assert.equal(await page.locator('[data-remove-follower]').count(), 30);
        // Removing the final remaining follower must update both counts and the empty view.
        followers = [followers[0]];
        await page.reload(); await page.locator('[data-people-tab="followers"]').click();
        await page.locator('[data-remove-follower]').click(); await page.locator('#alouteConfirmModal.show').waitFor();
        await page.locator('#alouteConfirmOk').click();
        await page.waitForFunction(() => document.querySelector('[data-people-tab="followers"] strong').textContent === '0');
        await page.waitForFunction(() => document.querySelector('#peopleModalBody').textContent.includes('Chưa có ai theo dõi.'));
        assert.equal(await page.locator('[data-remove-follower]').count(), 0);
      }
      assert.deepEqual(errors, []); console.log('PASS: ' + JSON.stringify(config)); await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
