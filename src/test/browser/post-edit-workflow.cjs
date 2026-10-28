const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  try {
    for (const kind of ['image', 'video', 'text']) {
      const page = await browser.newPage();
      const writes = [], errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.exposeFunction('captureEdit', body => writes.push(body));
      await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<body data-current-user-id="1"><article class="post" data-post-id="owned"><div class="post__menu-list"><button class="post__delete">Delete</button></div></article>' }));
      await page.goto('https://aloute.test/feed');
      await page.evaluate(type => {
        window.toasts = [];
        window.rejectEdit = true;
        window.Aloute = { toast: text => toasts.push(text), api: async (url, options = {}) => {
          if (options.method === 'PUT') {
            if (rejectEdit) throw new Error('Bài viết vừa thay đổi');
            await captureEdit(options.body); return null;
          }
          return { content: 'original caption', visibility: 'CLOSE_FRIENDS',
            imageUrl: type === 'image' ? 'https://res.cloudinary.com/derived/display-image.jpg' : null,
            videoUrl: type === 'video' ? 'https://res.cloudinary.com/derived/display-video.mp4' : null };
        } };
      }, kind);
      await page.addScriptTag({ content: fs.readFileSync('src/main/resources/static/js/features.js', 'utf8') });
      await page.locator('[data-post-edit]').click();
      assert.equal(await page.locator('dialog select').inputValue(), 'CLOSE_FRIENDS');
      assert.equal(await page.locator('dialog option').count(), 4);
      await page.locator('dialog textarea').fill(kind === 'text' ? 'new caption' : '');
      await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click();
      await page.waitForFunction(() => toasts.includes('Bài viết vừa thay đổi'));
      assert.equal(await page.locator('dialog').isVisible(), true);
      assert.equal(await page.locator('dialog textarea').inputValue(), kind === 'text' ? 'new caption' : '');
      await page.evaluate(() => { rejectEdit = false; });
      const loaded = page.waitForEvent('load');
      await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click();
      await loaded;
      assert.deepEqual(writes, [{ content: kind === 'text' ? 'new caption' : '', visibility: 'CLOSE_FRIENDS' }]);
      assert.deepEqual(errors, []); await page.close();
    }
    console.log(JSON.stringify({ passed: 3, results: 'Image/video/text edit, retained close-friends audience, failed-save retry, no display media URLs in request' }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
