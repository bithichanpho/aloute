const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async()=>{
  const output=process.env.ALOUTE_AUTH_EVIDENCE;assert.ok(output);fs.mkdirSync(output,{recursive:true});
  const staticRoot=path.resolve('src/main/resources/static');
  let handler,serverError;
  const server=require('node:http').createServer(async(req,res)=>{
    try {
      let body='';for await(const chunk of req)body+=chunk;
      const route={request:()=>({url:()=>origin+req.url,headers:()=>req.headers,postDataJSON:()=>JSON.parse(body)}),
        fulfill:async result=>{res.writeHead(result.status||200,{...(result.contentType?{'Content-Type':result.contentType}:{}),...result.headers});res.end(result.body||'');},
        abort:async()=>{res.writeHead(403);res.end();}};
      await handler(route);
    }catch(error){serverError=error;if(!res.headersSent)res.writeHead(500);res.end();}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin='http://127.0.0.1:'+server.address().port;
  const browser=await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  const results=[];
  try {
    for(const theme of ['dark','light'])for(const width of [1280,320])for(const motion of ['no-preference','reduce']) {
      const context=await browser.newContext({viewport:{width,height:width===320?640:900},reducedMotion:motion});
      await context.addInitScript(theme=>localStorage.setItem('aloute-theme',theme),theme);
      await context.addCookies([{name:'XSRF-TOKEN',value:'fixture-csrf',url:origin}]);
      const page=await context.newPage(),errors=[],calls=[];
      page.on('pageerror',error=>errors.push(error.message));
      let codeMode='wrong',completeMode='expired',validGrant=false,expectedGrant='',issued=0;
      const template=name=>fs.readFileSync(`src/main/resources/templates/auth/${name}.html`,'utf8')
        .replace(/\bth:(src|href)="@\{([^}]+)\}"/g,'$1="$2"').replace(/\s+th:value="[^"]*"/g,'');
      handler=async route=>{
        const u=new URL(route.request().url());
        if(['/forgot-password','/verify-reset-code','/reset-password'].includes(u.pathname)) {
          if(u.pathname==='/reset-password'&&(!validGrant||!route.request().headers().cookie?.includes('ALOUTE_PASSWORD_RESET='+expectedGrant)))
            return route.fulfill({status:302,headers:{Location:'/verify-reset-code'}});
          return route.fulfill({contentType:'text/html; charset=utf-8',body:template(u.pathname.slice(1)),headers:{'Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'"}});
        }
        if(u.pathname==='/login')return route.fulfill({contentType:'text/html',body:'<!doctype html><p>Login fixture</p>'});
        if(u.pathname.startsWith('/api/auth/')) {
          assert.equal(route.request().headers()['x-xsrf-token'],'fixture-csrf');
          const body=route.request().postDataJSON();calls.push({url:u.pathname,body});
          if(u.pathname==='/api/auth/forgot-password')return route.fulfill({contentType:'application/json',body:'{"success":true,"data":null}'});
          if(u.pathname==='/api/auth/verify-reset-code') {
            assert.deepEqual(Object.keys(body).sort(),['code','email']);
            if(codeMode==='wrong')return route.fulfill({status:400,contentType:'application/json',body:'{"success":false,"message":"Mã đặt lại mật khẩu không hợp lệ hoặc đã được dùng"}'});
            assert.equal(body.code,'012345');validGrant=true;expectedGrant='fixture-proof-'+(++issued);
            return route.fulfill({contentType:'application/json',headers:{'Set-Cookie':`ALOUTE_PASSWORD_RESET=${expectedGrant}; Path=/; HttpOnly; SameSite=Strict; Max-Age=300`},body:'{"success":true,"data":null}'});
          }
          if(u.pathname==='/api/auth/complete-password-reset') {
            assert.deepEqual(Object.keys(body),['newPassword']);
            assert.equal(body.newPassword,'MậtKhẩu١!');
            if(completeMode==='expired') {
              validGrant=false;return route.fulfill({status:400,contentType:'application/json',body:'{"success":false,"message":"Phiên đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng xác thực lại mã OTP."}'});
            }
            assert.ok(validGrant);assert.ok(route.request().headers().cookie.includes('ALOUTE_PASSWORD_RESET='+expectedGrant));validGrant=false;
            return route.fulfill({contentType:'application/json',headers:{'Set-Cookie':'ALOUTE_PASSWORD_RESET=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0'},body:'{"success":true,"data":null}'});
          }
          throw new Error('Unexpected auth endpoint '+u.pathname);
        }
        if(u.pathname.startsWith('/api/'))return route.fulfill({contentType:'application/json',body:'{"success":true,"data":{}}'});
        const file=path.resolve(staticRoot,'.'+u.pathname);
        if(!file.startsWith(staticRoot+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:''});
        return route.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'application/octet-stream'});
      };
      const focus=async id=>page.waitForFunction(id=>document.activeElement.id===id,id);
      await page.goto(origin+'/reset-password?email=victim%40example.invalid');
      await page.waitForURL('**/verify-reset-code');assert.equal(await page.locator('input[type="password"]').count(),0);
      await page.goto(origin+'/forgot-password');await page.locator('#email').fill('owner@example.invalid');await page.locator('#submitBtn').click();
      await page.waitForURL('**/verify-reset-code?email=*');assert.equal(await page.locator('#email').inputValue(),'owner@example.invalid');
      assert.equal(await page.locator('input[type="password"]').count(),0);
      await page.locator('#code').fill('000000');await page.locator('#submitBtn').click();
      await page.waitForFunction(()=>document.getElementById('code').value===''&&!document.getElementById('submitBtn').disabled);
      await focus('code');assert.equal(new URL(page.url()).pathname,'/verify-reset-code');assert.equal(await page.locator('input[type="password"]').count(),0);
      codeMode='accepted';await page.locator('#code').fill('012345');await page.locator('#submitBtn').click();await page.waitForURL('**/reset-password');
      assert.equal(await page.locator('#code,#email').count(),0);await focus('newPassword');
      assert.equal(await page.evaluate(()=>document.cookie.includes('ALOUTE_PASSWORD_RESET')),false);
      assert.equal(await page.evaluate(()=>sessionStorage.length),0);assert.equal(new URL(page.url()).search,'');
      const beforeComplete=calls.length;
      await page.locator('#newPassword').fill('weak');await page.locator('#confirmPassword').fill('weak');await page.locator('#submitBtn').click();await focus('newPassword');
      await page.locator('#newPassword').fill('MậtKhẩu١!');await page.locator('#submitBtn').click();await focus('confirmPassword');
      await page.locator('#newPassword').fill('Aa1!'+'ấ'.repeat(24));await page.locator('#confirmPassword').fill('Aa1!'+'ấ'.repeat(24));
      await page.locator('#submitBtn').click();await focus('newPassword');assert.equal(calls.length,beforeComplete);
      await page.locator('#newPassword').fill('MậtKhẩu١!');await page.locator('#confirmPassword').fill('MậtKhẩu١!');
      await page.waitForTimeout(400);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
      await page.screenshot({path:path.join(output,`password-page-${theme}-${width}-${motion}.png`),fullPage:true});
      await page.locator('#submitBtn').click();await focus('formError');assert.equal(await page.locator('#resetForm').isVisible(),false);
      await page.locator('a[href="/verify-reset-code"]').click();await page.waitForURL('**/verify-reset-code');
      await page.locator('#email').fill('owner@example.invalid');await page.locator('#code').fill('012345');await page.locator('#submitBtn').click();await page.waitForURL('**/reset-password');
      completeMode='accepted';await page.locator('#newPassword').fill('MậtKhẩu١!');await page.locator('#confirmPassword').fill('MậtKhẩu١!');
      await page.evaluate(()=>{document.getElementById('resetForm').requestSubmit();document.getElementById('resetForm').requestSubmit();});
      await page.waitForURL('**/login');assert.equal(calls.filter(call=>call.url.endsWith('/complete-password-reset')).length,2);
      await page.goto(origin+'/reset-password');await page.waitForURL('**/verify-reset-code');
      assert.equal(await page.locator('input[type="password"]').count(),0);assert.deepEqual(errors,[]);assert.equal(serverError,undefined);
      results.push({theme,width,motion,otpBeforePassword:true,expiredSessionHidden:true,passwordPolicy:true,noBrowserTokenStorage:true,singleFlight:true,errors});
      await context.close();
    }
    fs.writeFileSync(path.join(output,'reset-two-step.json'),JSON.stringify({configurations:results.length,results},null,2));
    console.log(JSON.stringify({configurations:results.length,results}));
  }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
