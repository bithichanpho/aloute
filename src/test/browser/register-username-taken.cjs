const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Real registration template and assets; all registration responses stay in this fixture.
(async () => {
  const staticRoot = path.resolve('src/main/resources/static');
  const html = fs.readFileSync('src/main/resources/templates/auth/register.html', 'utf8')
    .replace(/\bth:(src|href)="@\{([^}]+)\}"/g, '$1="$2"');
  const browser = await chromium.launch({ executablePath: process.env.ALOUTE_BROWSER_EXECUTABLE, headless: true });
  try {
    for (const theme of ['dark', 'light']) for (const width of [1280, 320]) for (const motion of ['no-preference', 'reduce']) {
      const context = await browser.newContext({ viewport: { width, height: 800 }, reducedMotion: motion });
      await context.addInitScript(theme => localStorage.setItem('aloute-theme', theme), theme);
      await context.addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-csrf', url: 'http://aloute.test' }]);
      const page = await context.newPage();
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      let mode = 'taken', submissions = 0, checks = 0;
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.hostname !== 'aloute.test') return route.abort();
        if (url.pathname === '/register') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
        if (url.pathname === '/verify-email') return route.fulfill({ contentType: 'text/html', body: '<p>OTP fixture</p>' });
        if (url.pathname === '/api/auth/register/username-availability') {
          checks++;
          assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf');
          await new Promise(resolve => setTimeout(resolve, 80));
          if (mode === 'check-failed') return route.fulfill({ status: 429, contentType: 'application/json', body: '{"success":false,"message":"Vui lòng thử lại sau."}' });
          if (mode === 'taken') assert.equal(route.request().postDataJSON().email, null);
          return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: { available: mode !== 'taken' } }) });
        }
        if (url.pathname === '/api/auth/register') {
          submissions++;
          assert.equal(route.request().headers()['x-xsrf-token'], 'fixture-csrf');
          await new Promise(resolve => setTimeout(resolve, 80));
          return route.fulfill({ status: ['taken', 'race'].includes(mode) ? 400 : mode === 'rate' ? 429 : 200,
            contentType: 'application/json', body: JSON.stringify(mode === 'success' ? { success: true, data: null }
              : { success: false, message: ['taken', 'race'].includes(mode) ? 'Tên đăng nhập đã được sử dụng' : 'Vui lòng thử lại sau.' }) });
        }
        if (url.pathname.startsWith('/api/')) return route.fulfill({ contentType: 'application/json', body: '{"success":true,"data":[]}' });
        const file = path.resolve(staticRoot, '.' + url.pathname);
        if (!file.startsWith(staticRoot + path.sep) || !fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
        const types = { '.js': 'application/javascript', '.css': 'text/css', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
        return route.fulfill({ contentType: types[path.extname(file)] || 'application/octet-stream', body: fs.readFileSync(file) });
      });
      await page.goto('http://aloute.test/register');
      for (const [id, value] of Object.entries({ fullName: 'Nguyễn An', username: 'existing_user', email: 'bad@', password: 'short' })) {
        await page.locator('#' + id).fill(value);
      }
      await page.evaluate(() => { const form = document.getElementById('registerForm'); form.requestSubmit(); form.requestSubmit(); });
      await page.waitForFunction(() => document.getElementById('username').getAttribute('aria-invalid') === 'true');
      const field = page.locator('[data-field="username"]');
      await page.waitForFunction(() => document.activeElement.id === 'username');
      assert.equal(await field.evaluate(node => node.classList.contains('is-bad')), true);
      assert.equal(await field.evaluate(node => node.classList.contains('is-good')), false);
      assert.equal(await page.locator('#username').getAttribute('aria-invalid'), 'true');
      assert.equal(await page.locator('#username').getAttribute('aria-describedby'), 'usernameError');
      assert.equal(await page.locator('#usernameError').innerText(), 'Tên đăng nhập đã được sử dụng');
      assert.equal(await page.locator('#formError').isVisible(), false);
      assert.equal(await page.locator('#password').getAttribute('aria-invalid'), 'true');
      assert.equal(await page.locator('#email').getAttribute('aria-invalid'), 'true');
      assert.equal(await page.locator('.u244-ct').innerText(), '3 trường cần kiểm tra');
      assert.equal(submissions, 0); assert.equal(checks, 1);
      const style = await page.locator('#username').evaluate(input => {
        const probe = document.createElement('span'); probe.style.color = 'var(--ux-danger)'; input.parentElement.append(probe);
        const danger = getComputedStyle(probe).color; probe.remove();
        return { shadow: getComputedStyle(input).boxShadow, danger,
          inlineColor: getComputedStyle(document.getElementById('usernameError')).color,
          boxColor: getComputedStyle(document.getElementById('formError')).color };
      });
      assert.ok(style.shadow.includes(style.danger), 'Username box uses the error color');
      assert.equal(style.inlineColor, style.boxColor);
      await page.locator('#fullName').fill('Nguyễn An mới');
      assert.equal(await page.locator('#username').getAttribute('aria-invalid'), 'true');
      await page.locator('#submitBtn').click(); await page.waitForTimeout(100);
      assert.equal(submissions, 0, 'Unchanged rejected username stays invalid');
      assert.equal(checks, 1, 'Do not repeat an unchanged rejected handle lookup');
      if (width === 320 && motion === 'reduce') {
        await page.screenshot({ path: `target/register-username-taken-${theme}.png`, fullPage: true });
      }
      await page.locator('#email').fill('register@example.invalid');
      await page.locator('#password').fill('Test-Password1!');
      await page.locator('#username').fill('available_user');
      assert.equal(await page.locator('#username').getAttribute('aria-invalid'), 'false');
      assert.equal(await page.locator('#formError').isVisible(), false);
      mode = 'rate'; await page.locator('#submitBtn').click();
      await page.waitForFunction(() => document.activeElement.id === 'formError');
      assert.equal(await page.locator('#username').getAttribute('aria-invalid'), 'false');
      assert.equal(await page.locator('#password').inputValue(), 'Test-Password1!');
      mode = 'success'; await page.locator('#submitBtn').click();
      await page.waitForURL('**/verify-email?email=*');
      assert.equal(new URL(page.url()).searchParams.get('email'), 'register@example.invalid');
      assert.equal(submissions, 2); assert.equal(checks, 3);
      // A concurrent registration can take a name after the read-only check.
      mode = 'race'; await page.goto('http://aloute.test/register');
      for (const [id, value] of Object.entries({ fullName: 'Nguyễn An', username: 'racing_user', email: 'register@example.invalid', password: 'Test-Password1!' })) {
        await page.locator('#' + id).fill(value);
      }
      await page.locator('#submitBtn').click();
      await page.waitForFunction(() => document.activeElement.id === 'username');
      assert.equal(await page.locator('#usernameError').innerText(), 'Tên đăng nhập đã được sử dụng');
      assert.equal(await page.locator('#formError').isVisible(), false);
      assert.equal(submissions, 3); assert.equal(checks, 4);
      if (theme === 'dark' && width === 1280 && motion === 'no-preference') {
        mode = 'check-failed'; await page.goto('http://aloute.test/register');
        for (const [id, value] of Object.entries({ fullName: 'Nguyễn An', username: 'available_user', email: 'register@example.invalid', password: 'Test-Password1!' })) {
          await page.locator('#' + id).fill(value);
        }
        await page.locator('#submitBtn').click();
        await page.waitForFunction(() => document.activeElement.id === 'formError');
        assert.equal(submissions, 3, 'Unavailable preflight must not submit registration');
        assert.equal(await page.locator('#username').evaluate(input => input.readOnly), false);
        assert.equal(await page.locator('#username').getAttribute('aria-invalid'), 'false');
      }
      assert.deepEqual(errors, []);
      console.log(JSON.stringify({ theme, width, motion, result: 'PASS' }));
      await context.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
