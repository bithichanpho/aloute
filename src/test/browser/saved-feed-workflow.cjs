const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  const template = fs.readFileSync('target/saved-feed-template.html', 'utf8');
  try {
    for (const width of [1280, 390, 320]) for (const theme of ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width, height: 950 } });
      const errors = [], calls = [], comments = [];
      let rejectUnsave = true;
      page.on('pageerror', error => errors.push(error.message));
      await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'saved-fixture', url: 'http://aloute.test' }]);
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url()), path = url.pathname;
        if (url.origin !== 'http://aloute.test') return route.fulfill({ status: 204, body: '' });
        const reply = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
        if (path.startsWith('/api/')) {
          if (request.method() !== 'GET') {
            assert.equal(request.headers()['x-xsrf-token'], 'saved-fixture');
            calls.push({ method: request.method(), path, body: request.postData() ? request.postDataJSON() : null });
          }
          if (path.endsWith('/saved') && request.method() === 'DELETE') {
            if (rejectUnsave) return route.fulfill({ status: 403, contentType: 'application/json', body: '{"success":false,"message":"Không thể bỏ lưu lúc này"}' });
            await new Promise(resolve => setTimeout(resolve, 50));
            return reply(null);
          }
          if (path.endsWith('/react')) {
            const type = request.postDataJSON().type;
            return reply({ currentUserReaction: type, totalCount: type ? 1 : 0, reactionCounts: type ? { [type]: 1 } : {} });
          }
          if (path.endsWith('/repost')) return reply({ reposted: true, repostCount: 1, reposters: [], otherReposterCount: 0 });
          if (path.endsWith('/comments')) {
            if (request.method() === 'POST') {
              const content = request.postDataJSON().content;
              comments.push({ id: 'comment-fixture', userId: 1, username: 'tester', postId: 'plain-saved', content,
                timeAgo: 'Vừa xong', moderationStatus: 'APPROVED', reactionCounts: {} });
              return reply(comments.at(-1));
            }
            return reply(comments);
          }
          if (path.endsWith('/friends')) return reply([]);
          return reply({ count: 0 });
        }
        if (path === '/fixture/saved-image.svg') return route.fulfill({ contentType: 'image/svg+xml', body:
          '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="500"><rect width="800" height="500" fill="#547489"/></svg>' });
        if (path === '/fixture/saved-video.mp4') return route.fulfill({ status: 204, body: '' });
        if (path.endsWith('.js')) return route.fulfill({ contentType: 'application/javascript', body:
          ['/js/app.js', '/js/features.js'].includes(path) ? fs.readFileSync('src/main/resources/static' + path) : '' });
        const asset = 'src/main/resources/static' + path;
        if (fs.existsSync(asset) && fs.statSync(asset).isFile()) return route.fulfill({ contentType:
          path.endsWith('.css') ? 'text/css' : path.endsWith('.woff2') ? 'font/woff2' : 'image/png', body: fs.readFileSync(asset) });
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: template.replace('data-bs-theme="dark"', `data-bs-theme="${theme}"`) });
      });
      await page.goto('http://aloute.test/saved');
      const posts = page.locator('.saved-stream > .post');
      assert.equal(await posts.count(), 5);
      assert.equal(await page.locator('.action--save.is-saved[aria-pressed="true"]').count(), 5);
      assert.equal(await page.locator('.feature-saved__body').count(), 0);
      const plain = page.locator('[data-saved-post="plain-saved"]');
      const photo = page.locator('[data-saved-post="image-saved"]');
      await photo.locator('.post__image').evaluate(image => image.decode());
      assert.ok((await photo.boundingBox()).height > (await plain.boundingBox()).height, 'Posts must grow naturally with their media');
      const overflow = await posts.evaluateAll(cards => cards.map(card => card.scrollWidth > card.clientWidth));
      if (overflow.some(Boolean)) {
        console.log(JSON.stringify(await posts.evaluateAll(cards => cards.map(card => ({id:card.dataset.savedPost,width:card.clientWidth,scroll:card.scrollWidth,children:[...card.querySelectorAll('.post__body,.post__header,.post__actions,.reaction-picker,.repost-card')].map(el=>({class:el.className,width:el.clientWidth,scroll:el.scrollWidth,x:el.getBoundingClientRect().x}))})))));
        await page.screenshot({path:'target/saved-feed-overflow.png'});
      }
      assert.ok(overflow.every(value => !value), 'Post cards must fit the screen');
      const geometry = await page.locator('.saved-stream').boundingBox();
      assert.ok(geometry.x >= 0 && geometry.x + geometry.width <= width);
      assert.equal(await plain.locator('.post__avatar').count(), 1);
      assert.equal(await page.locator('[data-saved-post="flagged-saved"] .post__moderation-status--flagged').count(), 1);
      if (width !== 320) await page.screenshot({ path: `target/saved-feed-${width}-${theme}.png` });

      await plain.locator('.action--like').click();
      await page.waitForFunction(() => document.querySelector('[data-saved-post="plain-saved"] .action--like [data-raw]').dataset.raw === '1');
      await plain.locator('.action--like').click();
      await page.waitForFunction(() => !document.querySelector('[data-saved-post="plain-saved"] .action--like').classList.contains('is-reacted'));
      await plain.locator('.action--reply').click();
      await plain.locator('.comment-form input').fill('Bình luận từ trang Đã lưu');
      await plain.locator('.comment-form button[type="submit"]').click();
      await plain.locator('.comment-item').waitFor();
      await plain.locator('.action--repost').click();
      await page.waitForFunction(() => document.querySelector('[data-saved-post="plain-saved"] .action--repost span').dataset.raw === '1');
      await plain.locator('.action--share').click();
      await page.locator('.share-post-dialog[open]').waitFor();
      await page.locator('.share-post-dialog__close').click();

      const quote = page.locator('[data-saved-post="quote-saved"]');
      const rejected = page.waitForResponse(response => response.url().endsWith('/quote-saved/saved') && response.status() === 403);
      await quote.locator('.action--save').click();
      await rejected;
      await page.waitForFunction(() => !document.querySelector('[data-saved-post="quote-saved"] .action--save').disabled);
      assert.equal(await quote.locator('.action--save').getAttribute('aria-pressed'), 'true');
      rejectUnsave = false;
      await quote.locator('.action--save').evaluate(button => { button.click(); button.click(); });
      await quote.waitFor({ state: 'detached' });
      assert.equal(calls.filter(call => call.path === '/api/posts/quote-saved/saved').length, 2, 'Retry must not send duplicate deletes');
      assert.ok(!calls.some(call => call.path === '/api/posts/original-post/saved'), 'Unsave must address the saved wrapper, not its repost origin');
      for (const id of ['plain-saved', 'image-saved', 'video-saved', 'flagged-saved']) {
        const card = page.locator(`[data-saved-post="${id}"]`);
        await card.locator('.action--save').click();
        await card.waitFor({ state: 'detached' });
      }
      assert.equal(await posts.count(), 0);
      assert.ok((await page.locator('.saved-stream .feature-empty').innerText()).includes('Chưa có bài viết đã lưu'));
      assert.deepEqual(calls.filter(call => call.path.endsWith('/react')).map(call => call.body.type), ['LIKE', null]);
      assert.ok(calls.some(call => call.path === '/api/posts/plain-saved/comments' && call.body.content === 'Bình luận từ trang Đã lưu'));
      assert.deepEqual(errors, []);
      console.log(`PASS: saved feed ${width}/${theme}, natural cards, reaction/removal, comment, repost, share, unsave failure/retry, quote target, empty state`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
