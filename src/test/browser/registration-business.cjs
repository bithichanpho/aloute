const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async()=>{
  const before = process.argv.includes('before');
  const source = fs.readFileSync('src/main/resources/templates/auth/register.html','utf8').replace(/\bth:(src|href)="@\{([^}]+)\}"/g,'$1="$2"');
  const cases = JSON.parse(fs.readFileSync('src/test/resources/registration-validation-cases.json','utf8'));
  const browser = await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  const report = {before,cases:[],requests:0};
  try {
    const context=await browser.newContext({reducedMotion:'reduce'});
    await context.addCookies([{name:'XSRF-TOKEN',value:'fixture',url:'http://aloute.test'}]);
    const page=await context.newPage();
    let mode='reject', release, requests=0, resends=0, retainCompletedDocument=false;
    const held=new Promise(resolve=>release=resolve);
    let retainedResponseReady;
    const retainedResponse=new Promise(resolve=>retainedResponseReady=resolve);
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.route('**/*',async route=>{
      const url=new URL(route.request().url());
      if(url.hostname!=='aloute.test')return route.abort();
      if(url.pathname==='/register')return route.fulfill({contentType:'text/html; charset=utf-8',body:source});
      if(url.pathname==='/api/auth/register'){
        requests++;
        if(mode==='hang')return;
        if(mode==='hold')await held;
        if(mode==='accepted')return route.fulfill({contentType:'application/json',body:'{"success":true,"message":"Đã ghi nhận đăng ký. Vui lòng kiểm tra email (cả thư rác) để hoàn tất.","data":{}}'});
        return route.fulfill({status:400,contentType:'application/json',body:'{"success":false,"message":"Tên đăng nhập đã được sử dụng","data":null}'});
      }
      if(url.pathname==='/api/auth/resend-verification'){
        resends++;
        if(mode==='resend-hang')return;
        return route.fulfill({contentType:'application/json',body:'{"success":true,"message":"Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email","data":null}'});
      }
      if(url.pathname==='/verify-email') {
        if(retainCompletedDocument) {
          await route.fulfill({status:204,body:''}); retainedResponseReady(); return;
        }
        return route.fulfill({contentType:'text/html',body:'OTP fixture'});
      }
      if(url.pathname.startsWith('/api/'))return route.fulfill({contentType:'application/json',body:'{"success":true,"data":[]}'});
      const root=path.resolve('src/main/resources/static'); const file=path.resolve(root,'.'+url.pathname);
      if(!file.startsWith(root+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:''});
      return route.fulfill({contentType:file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'application/octet-stream',body:fs.readFileSync(file)});
    });
    await page.goto('http://aloute.test/register');
    await page.locator('#submitBtn').click();
    const standard={fullName:'Test User',username:'business_test',email:'test@example.invalid',password:'Test-Password1!'};
    const assign=async values=>page.evaluate(values=>{
      for(const [id,value]of Object.entries(values)){const el=document.getElementById(id);el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));}
    },values);
    for(const fixture of cases){
      const value=fixture.value ?? (fixture.prefix||'')+fixture.repeat.repeat(fixture.times)+(fixture.suffix||'');
      await assign({...standard,[fixture.field]:value});
      const valid=await page.locator('#'+fixture.field).getAttribute('aria-invalid')==='false';
      report.cases.push({id:fixture.id,expected:fixture.valid,valid});
    }
    report.mismatches=report.cases.filter(row=>row.expected!==row.valid).map(row=>row.id);
    if(!before)assert.deepEqual(report.mismatches,[],'Client and backend business cases must agree');
    await assign(standard);
    // Request while editing: ordinary typing must not change the identity being submitted.
    mode='hold'; await page.locator('#submitBtn').click();
    for(let i=0;i<50 && requests===0;i++)await page.waitForTimeout(10);
    assert.equal(requests,1);
    report.readOnlyDuringRequest=await page.locator('#email').evaluate(el=>el.readOnly);
    if(!before)assert.equal(report.readOnlyDuringRequest,true);
    release();
    await page.waitForFunction(()=>!document.getElementById('submitBtn').disabled);
    assert.equal(await page.locator('#formError').isVisible(),true);
    // A timeout cannot be presented as registration failure with a known server outcome.
    mode='hang'; await page.clock.install(); await page.locator('#submitBtn').click();
    await page.clock.fastForward(31000); await page.waitForTimeout(100);
    report.recoveredFromHang=!(await page.locator('#submitBtn').isDisabled());
    report.stayedOnForm=new URL(page.url()).pathname==='/register';
    if(!before){
      assert.equal(report.recoveredFromHang,true); assert.equal(report.stayedOnForm,true);
      assert.match(await page.locator('#formError').textContent(),/chưa xác định/i);
      assert.equal(await page.locator('#email').evaluate(el=>el.readOnly),false);
      // Legacy pending UI stays compatible, but resend must use the real neutral API envelope.
      await page.evaluate(()=>document.getElementById('pendingVerificationBox').hidden=false);
      mode='reject'; await page.locator('#resendPendingBtn').click();
      await page.waitForFunction(()=>!document.getElementById('resendPendingBtn').disabled);
      assert.match(await page.locator('#resendPendingMessage').textContent(),/^Nếu tài khoản tồn tại/);
      mode='resend-hang'; await page.locator('#resendPendingBtn').click(); await page.clock.fastForward(31000);
      await page.waitForFunction(()=>!document.getElementById('resendPendingBtn').disabled);
      assert.equal(await page.locator('#submitBtn').isDisabled(),false);
      report.resendTimeoutRecovered=true;
      // Retain the actual completed DOM, then simulate the browser's persisted pageshow event.
      // This checks restoration state; it does not claim to measure real BFCache eligibility.
      retainCompletedDocument=true; mode='accepted'; await page.locator('#submitBtn').click();
      await page.waitForFunction(()=>document.getElementById('submitBtn').classList.contains('is-done'));
      await retainedResponse; await page.waitForTimeout(100);
      assert.equal(new URL(page.url()).pathname,'/register');
      await page.evaluate(()=>dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true})));
      assert.equal(await page.locator('#email').evaluate(el=>el.readOnly),false);
      assert.equal(await page.locator('#submitBtn').isDisabled(),false);
      report.restoredCompletedForm=true; retainCompletedDocument=false;
      // Both new-email and pending-email successful responses retain the OTP redirect.
      mode='accepted'; await page.locator('#submitBtn').click(); await page.waitForURL('**/verify-email?email=*');
      assert.equal(requests,4,'A restored completed form must actually permit a fresh submit');
      assert.equal(new URL(page.url()).searchParams.get('email'),standard.email);
      report.acceptedRedirect=true;
    }
    report.requests=requests; report.resends=resends; assert.deepEqual(errors,[]);
    console.log(JSON.stringify(report));
    await context.close();
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
