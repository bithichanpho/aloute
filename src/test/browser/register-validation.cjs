const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Exercise the real template, styles and scripts; API responses are isolated HTTP fixtures.
(async () => {
  const before = process.argv.includes('before');
  const output = process.env.ALOUTE_REGISTER_EVIDENCE;
  assert.ok(output, 'Configure a test evidence directory');
  fs.mkdirSync(output, {recursive:true});
  const backup = process.env.ALOUTE_REGISTER_BACKUP;
  const staticRoot = path.resolve('src/main/resources/static');
  const templatePath = before ? path.join(backup,'src/main/resources/templates/auth/register.html')
    : 'src/main/resources/templates/auth/register.html';
  const html = fs.readFileSync(templatePath,'utf8').replace(/\bth:(src|href)="@\{([^}]+)\}"/g,'$1="$2"');
  const browser = await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  const results = [];
  try {
    for (const theme of ['dark','light']) for (const width of [1280,320]) for (const motion of ['no-preference','reduce']) {
      if (before && (theme !== 'dark' || width !== 1280 || motion !== 'no-preference')) continue;
      const name = `${theme}-${width}-${motion}`;
      const height = width === 320 ? 640 : 900;
      const context = await browser.newContext({viewport:{width,height},reducedMotion:motion});
      await context.addInitScript(theme => localStorage.setItem('aloute-theme',theme),theme);
      await context.addCookies([{name:'XSRF-TOKEN',value:'fixture-csrf',url:'http://aloute.test'}]);
      const page = await context.newPage();
      const errors = [], requests = [];
      let mode = 'pending', resends = 0;
      page.on('pageerror', error => errors.push(error.message));
      page.on('console', message => { if(message.type()==='error') errors.push(message.text()); });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.hostname !== 'aloute.test') return route.abort();
        if (url.pathname === '/register') return route.fulfill({contentType:'text/html; charset=utf-8',body:html,
          headers:{'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'"}});
        if (url.pathname === '/verify-email') return route.fulfill({contentType:'text/html',body:'<!doctype html><p>OTP route fixture</p>'});
        if (url.pathname === '/api/auth/register') {
          requests.push({body:route.request().postDataJSON(),csrf:route.request().headers()['x-xsrf-token']});
          await new Promise(resolve => setTimeout(resolve,150));
          return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(mode === 'success'
            ? {success:true,data:{userId:null,accessToken:null}}
            : {success:false,message:'Tài khoản đang chờ xác thực. Hãy kiểm tra email.'})});
        }
        if (url.pathname === '/api/auth/resend-verification') {
          assert.equal(route.request().method(),'POST');
          assert.equal(route.request().headers()['x-xsrf-token'],'fixture-csrf');
          assert.deepEqual(route.request().postDataJSON(),{identifier:'register-test@example.invalid'});
          resends++;
          return route.fulfill({contentType:'application/json',body:'{"success":true,"message":"Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email","data":null}'});
        }
        if (url.pathname.startsWith('/api/')) return route.fulfill({contentType:'application/json',body:'{"success":true,"data":[]}'});
        const file = path.resolve(staticRoot,'.'+url.pathname);
        if (!file.startsWith(staticRoot+path.sep)) return route.abort();
        let target = file;
        if (before && url.pathname === '/js/auth-register.js') target = path.join(backup,'src/main/resources/static/js/auth-register.js');
        if (!fs.existsSync(target) || !fs.statSync(target).isFile()) return route.fulfill({status:404,body:''});
        const type = {'.css':'text/css; charset=utf-8','.js':'application/javascript; charset=utf-8','.svg':'image/svg+xml','.woff2':'font/woff2','.png':'image/png'}[path.extname(target)] || 'application/octet-stream';
        return route.fulfill({contentType:type,body:fs.readFileSync(target)});
      });
      await page.goto('http://aloute.test/register');
      await page.waitForFunction(()=>document.documentElement.dataset.bsTheme === localStorage.getItem('aloute-theme'));
      await page.screenshot({path:path.join(output,`${before?'before':'initial'}-${name}.png`),fullPage:true});
      if(!before && width === 1280 && motion === 'no-preference') await page.locator('.u244').screenshot({path:path.join(output,`preview-${theme}.png`)});
      await page.locator('#password').focus();
      await page.keyboard.press('Enter');
      if(before) {
        assert.equal(await page.locator('#formError').textContent(),'Vui lòng điền đầy đủ thông tin.');
        results.push({before:true,genericError:true,invalidFieldCount:await page.locator('[aria-invalid="true"]').count()});
        await context.close(); continue;
      }
      await page.waitForFunction(()=>document.activeElement.id === 'fullName');
      assert.equal(await page.locator('.u244-ct').textContent(),'4 trường cần kiểm tra');
      assert.equal(await page.locator('.u244-fd.is-bad').count(),4);
      assert.equal(requests.length,0);
      assert.equal(await page.locator('#fullName').getAttribute('aria-describedby'),'fullNameError');
      assert.ok((await page.locator('#password').getAttribute('aria-describedby')).includes('passwordHelp'));
      const box = await page.locator('#fullName').boundingBox();
      const scroller = await page.locator('.u244-sc').boundingBox();
      assert.ok(box.y >= scroller.y && box.y+box.height <= scroller.y+scroller.height,'First invalid field must be visible');
      if(motion === 'reduce') {
        assert.equal(await page.locator('.u244-bx').first().evaluate(el=>getComputedStyle(el).animationName),'none');
        // The shared theme enforces 0.01ms !important; both that value and zero suppress motion.
        assert.ok(await page.locator('.u244-sm').evaluate(el=>getComputedStyle(el).transitionDuration.split(',').every(value=>parseFloat(value)<=0.00001)));
      }
      await page.screenshot({path:path.join(output,`errors-${name}.png`),fullPage:true});
      // Links are real keyboard-operable anchors; Enter must scroll/focus the matching input.
      await page.locator('.u244-sb a[href="#password"]').focus();
      await page.keyboard.press('Enter');
      await page.waitForFunction(()=>document.activeElement.id === 'password');
      await page.locator('#fullName').fill('  Nguyễn An  ');
      assert.equal(await page.locator('.u244-ct').textContent(),'3 trường cần kiểm tra');
      assert.equal(await page.locator('.u244-sb li[data-for="fullName"]').getAttribute('class'),'is-fixed');
      await page.locator('#username').fill('-bad');
      assert.equal(await page.locator('#username').getAttribute('aria-invalid'),'true');
      await page.locator('#username').fill('aloute_testuser');
      assert.equal(await page.locator('.u244-ct').textContent(),'2 trường cần kiểm tra');
      await page.locator('#email').fill('an@');
      assert.equal(await page.locator('#email').getAttribute('aria-invalid'),'true');
      await page.locator('#email').fill('register-test@example.invalid');
      assert.equal(await page.locator('.u244-ct').textContent(),'1 trường cần kiểm tra');
      await page.locator('#password').fill('abc');
      assert.equal(await page.locator('#password').getAttribute('aria-invalid'),'true');
      await page.locator('#password').fill('Test-Password1!');
      assert.equal(await page.locator('.u244-fd.is-good').count(),4);
      assert.equal(await page.locator('.u244-sm').evaluate(el=>el.inert),true);
      assert.equal(await page.locator('.u244').evaluate(el=>el.classList.contains('is-err')),false);
      assert.equal(await page.locator('#password').getAttribute('aria-describedby'),'passwordHelp');
      assert.equal(requests.length,0,'Corrections must not submit automatically');
      // Reintroducing a mistake must restore its summary entry and error hint.
      await page.locator('#email').fill('bad@');
      assert.equal(await page.locator('.u244-ct').textContent(),'1 trường cần kiểm tra');
      assert.equal(await page.locator('.u244-sb li[data-for="email"]').getAttribute('class'),'');
      await page.locator('#email').fill('register-test@example.invalid');
      await page.waitForTimeout(420);
      assert.equal(await page.locator('.u244-ms').first().evaluate(el=>el.getBoundingClientRect().height),0);
      const overflow = await page.evaluate(()=>({document:document.documentElement.scrollWidth>innerWidth,form:document.querySelector('.u244-sc').scrollWidth>document.querySelector('.u244-sc').clientWidth}));
      assert.deepEqual(overflow,{document:false,form:false});
      // A server refusal must retain values, restore the button and keep the existing OTP resend flow.
      await page.locator('#submitBtn').click();
      await page.waitForFunction(()=>document.activeElement.id === 'formError');
      assert.equal(await page.locator('#pendingVerificationBox').isVisible(),true);
      assert.equal(await page.locator('#submitBtn').isDisabled(),false);
      assert.equal(await page.locator('#password').inputValue(),'Test-Password1!');
      await page.locator('#resendPendingBtn').click();
      await page.waitForFunction(()=>document.getElementById('resendPendingMessage').textContent.startsWith('Nếu tài khoản tồn tại'));
      mode = 'success';
      await page.evaluate(()=>{ const form=document.getElementById('registerForm'); form.requestSubmit(); form.requestSubmit(); });
      await page.waitForURL('**/verify-email?email=*');
      assert.equal(new URL(page.url()).searchParams.get('email'),'register-test@example.invalid');
      assert.equal(requests.length,2,'Each submit attempt must send only one request');
      for(const request of requests) {
        assert.equal(request.csrf,'fixture-csrf');
        assert.deepEqual(request.body,{fullName:'Nguyễn An',username:'aloute_testuser',email:'register-test@example.invalid',password:'Test-Password1!'});
      }
      assert.equal(resends,1);
      assert.deepEqual(errors,[],'No JavaScript or CSP errors');
      results.push({theme,width,height,motion,keyboard:true,firstErrorFocused:true,corrections:true,noOverflow:true,serverErrorRetry:true,otpResend:true,csrfAndPayloadPreserved:true,registrationRequests:requests.length,errors});
      await context.close();
    }
    fs.writeFileSync(path.join(output,before?'before.json':'after.json'),JSON.stringify({before,results},null,2));
    console.log(JSON.stringify({before,matrices:results.length,results}));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
