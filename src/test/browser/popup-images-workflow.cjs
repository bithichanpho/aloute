const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
const liveAssetOrigin = process.env.ALOUTE_POPUP_ASSET_ORIGIN;
const shell = fs.readFileSync('target/search-template.html', 'utf8');
const html = shell.replace('<section class="content-inner search-page">', `<section class="content-inner search-page">
  <div id="friendsPanel"><button data-friend-chat-open data-user-id="2" data-fullname="Bạn gửi ảnh">Mở chat bạn</button></div>
  <button data-group-chat-open data-group-id="9" data-group-name="Nhóm ảnh">Mở chat nhóm</button>`);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  try {
    for (const width of [1280, 390]) for (const theme of ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width, height: 900 } });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => {
        const image = (id, senderId, mediaType, url, content = '') => ({ id, senderId, receiverId: senderId === 1 ? 2 : 1,
          mediaType, mediaUrl: 'https://aloute.test/fixture/' + url, mediaName: 'Ảnh đính kèm', content, read: true });
        window.Aloute = {
          onRealtimeReady(callback) { callback({ send() {} }); }, refreshNavBadges() {}, toast() {},
          escapeHtml: value => String(value ?? '').replace(/[&<>"']/g, ch => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
          }[ch])),
          api: async path => path === '/api/chat/messages/2' ? [
            image('received', 2, 'image', 'landscape.svg'),
            image('sent', 1, 'IMAGE', 'portrait.svg', 'Ảnh có chú thích'),
            image('file', 2, 'file', 'document.txt'),
            image('broken', 2, 'image', 'broken.png')
          ] : path === '/api/chat/group/9/messages' ? [
            { ...image('group-image', 3, 'image', 'landscape.svg'), receiverId: null, groupId: 9, senderFullName: 'Thành viên' }
          ] : []
        };
      });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://aloute.test') return route.fulfill({ status: 204, body: '' });
        if (url.pathname === '/fixture/broken.png') return route.fulfill({ status: 404, body: '' });
        if (url.pathname.startsWith('/fixture/') && url.pathname.endsWith('.svg')) {
          const portrait = url.pathname.endsWith('portrait.svg');
          return route.fulfill({ contentType: 'image/svg+xml', body:
            `<svg xmlns="http://www.w3.org/2000/svg" width="${portrait ? 600 : 1200}" height="${portrait ? 1200 : 800}" viewBox="0 0 600 400"><rect width="600" height="400" fill="#125c78"/><circle cx="430" cy="100" r="48" fill="#ffe599"/><path d="M0 400 160 150 290 320 400 220 600 400" fill="#53b89c"/></svg>` });
        }
        if (url.pathname.endsWith('.js') && !['/js/calls.js', '/js/friends-panel.js'].includes(url.pathname)) {
          return route.fulfill({ contentType: 'application/javascript', body: '' });
        }
        const file = 'src/main/resources/static' + url.pathname;
        if (fs.existsSync(file) && fs.statSync(file).isFile()) {
          let body = fs.readFileSync(file);
          if (liveAssetOrigin) {
            const response = await fetch(liveAssetOrigin + url.pathname + '?v=20261028-popup-images-v2',
              { headers: { 'Cache-Control': 'no-cache' } });
            assert.ok(response.ok, 'Running application must serve ' + url.pathname);
            body = Buffer.from(await response.arrayBuffer());
          }
          return route.fulfill({ contentType:
            url.pathname.endsWith('.js') ? 'application/javascript' : url.pathname.endsWith('.css') ? 'text/css'
              : url.pathname.endsWith('.woff2') ? 'font/woff2' : 'image/png', body });
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      });
      await page.goto('https://aloute.test/feed');
      await page.evaluate(value => document.documentElement.setAttribute('data-bs-theme', value), theme);
      await page.locator('[data-friend-chat-open]').click();
      const popup = page.locator('.friends-chat-window[data-friend-id="2"]');
      for (const id of ['received', 'sent']) {
        const image = popup.locator(`[data-message-id="${id}"] .friends-chat-window__attachment img`);
        await image.scrollIntoViewIfNeeded();
        await image.evaluate(element => element.decode());
        assert.equal(await image.evaluate(element => element.naturalWidth > 0), true);
        const anchor = image.locator('..');
        assert.equal(await anchor.getAttribute('target'), '_blank');
        const imageBox = await image.boundingBox(), popupBox = await popup.boundingBox();
        const anchorBox = await anchor.boundingBox();
        const natural = await image.evaluate(element => ({ width: element.naturalWidth, height: element.naturalHeight }));
        assert.ok(imageBox.width > 0 && imageBox.width <= 260 && imageBox.height <= 300);
        assert.ok(imageBox.x >= popupBox.x && imageBox.x + imageBox.width <= popupBox.x + popupBox.width);
        assert.ok(Math.abs(imageBox.width - imageBox.height * natural.width / natural.height) < 2, 'Keep image proportions');
        assert.ok(Math.abs(anchorBox.width - imageBox.width) < 1, 'Image frame fits the image width');
        assert.ok(Math.abs(anchorBox.height - imageBox.height) < 1, 'Image frame fits the image height');
        assert.equal(await anchor.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(0, 0, 0)');
        assert.equal(await anchor.evaluate(element => !!element.closest('.friends-chat-window__bubble')), false,
          'Attachment must not inherit the blue text bubble background');
      }
      assert.ok((await popup.locator('[data-message-id="sent"]').innerText()).includes('Ảnh có chú thích'));
      assert.ok((await popup.locator('[data-message-id="sent"]').innerText()).includes('Đã xem'));
      assert.equal(await popup.locator('[data-message-id="file"] img').count(), 1, 'File keeps sender avatar only');
      await popup.locator('[data-message-id="broken"] .has-error').waitFor();
      assert.ok((await popup.locator('[data-message-id="broken"]').innerText()).includes('Bấm để mở ảnh'));
      await page.evaluate(() => document.dispatchEvent(new CustomEvent('aloute:message', { detail: {
        id: 'live-image', senderId: 2, receiverId: 1, mediaType: 'image', mediaUrl: 'https://aloute.test/fixture/landscape.svg'
      } })));
      await popup.locator('[data-message-id="live-image"] .friends-chat-window__attachment img').evaluate(element => element.decode());
      await page.waitForFunction(() => {
        const messages = document.querySelector('.friends-chat-window__messages');
        return messages.scrollHeight - messages.scrollTop - messages.clientHeight < 8;
      });
      await page.screenshot({ path: `target/popup-images-${width}-${theme}.png`, animations: 'disabled' });
      await page.locator('[data-group-chat-open]').click();
      const groupImage = page.locator('.friends-chat-window[data-group-id="9"] .friends-chat-window__attachment img');
      await groupImage.evaluate(element => element.decode());
      assert.equal(await groupImage.evaluate(element => element.naturalWidth > 0), true);
      assert.deepEqual(errors, []);
      console.log(`PASS: ${width}/${theme}, history/live/group images, sizing, captions, receipts, file links and failed-image fallback`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
