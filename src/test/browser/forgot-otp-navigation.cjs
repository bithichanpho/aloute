const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

// Real auth resources with isolated HTTP responses; no account, mail or production access.
(async()=>{
  const output=process.env.ALOUTE_AUTH_EVIDENCE;assert.ok(output);fs.mkdirSync(output,{recursive:true});
  const baseline=process.argv.includes('before'), results=[];
  const browser=await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  const staticRoot=path.resolve('src/main/resources/static');
  const template=fs.readFileSync('src/main/resources/templates/auth/forgot-password.html','utf8')
    .replace(/\bth:(src|href)="@\{([^}]+)\}"/g,'$1="$2"');
  try {
    for(const theme of ['dark','light'])for(const width of [1280,320])for(const motion of ['no-preference','reduce']) {
      if(baseline&&(theme!=='dark'||width!==1280||motion!=='reduce'))continue;
      for(const scenario of ['normal','suspended-timers','retained-document','rejected']) {
        const context=await browser.newContext({viewport:{width,height:900},reducedMotion:motion});
        await context.addInitScript(theme=>localStorage.setItem('aloute-theme',theme),theme);
        await context.addCookies([{name:'XSRF-TOKEN',value:'fixture-csrf',url:'http://aloute.test'}]);
        const page=await context.newPage(), errors=[], calls=[];
        page.on('pageerror',error=>errors.push(error.message));
        let mode=scenario==='rejected'?'rate':'held', release;
        const held=new Promise(resolve=>release=resolve);
        let reachedDestination=0;
        await page.route('**/*',async route=>{
          const url=new URL(route.request().url());
          if(url.hostname!=='aloute.test')return route.abort();
          if(url.pathname==='/forgot-password')return route.fulfill({contentType:'text/html; charset=utf-8',body:template});
          if(url.pathname==='/verify-reset-code') {
            reachedDestination++;
            return scenario==='retained-document'&&reachedDestination===1
              ?route.fulfill({status:204,body:''})
              :route.fulfill({contentType:'text/html',body:'<!doctype html><h1>OTP fixture</h1>'});
          }
          if(url.pathname==='/api/auth/forgot-password') {
            assert.equal(route.request().method(),'POST');
            assert.equal(route.request().headers()['x-xsrf-token'],'fixture-csrf');
            calls.push(route.request().postDataJSON());
            if(mode==='held')await held;
            if(mode==='network')return route.abort('failed');
            return route.fulfill({status:mode==='rate'?429:200,contentType:'application/json',
              body:JSON.stringify({success:mode!=='rate',message:mode==='rate'?'Bạn đã thử quá nhiều lần. Vui lòng thử lại sau.':'Nếu email tồn tại, yêu cầu đã được ghi nhận',data:null})});
          }
          if(url.pathname.startsWith('/api/'))return route.fulfill({contentType:'application/json',body:'{"success":true,"data":{}}'});
          const file=path.resolve(staticRoot,'.'+url.pathname);
          if(!file.startsWith(staticRoot+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:''});
          return route.fulfill({contentType:file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'application/octet-stream',body:fs.readFileSync(file)});
        });
        const result={theme,width,motion,scenario};
        try {
          await page.goto('http://aloute.test/forgot-password');
          await page.locator('#email').fill('otp+test@example.invalid');
          if(scenario==='suspended-timers') {
            // Browser backgrounding can suspend JS timers; a successful ACK must still navigate.
            await page.clock.install();await page.clock.pauseAt(new Date(Date.now()+100));
          }
          if(scenario==='suspended-timers')await page.locator('#submitBtn').evaluate(button=>button.click());
          else await page.locator('#submitBtn').click();
          if(scenario==='rejected') {
            await page.waitForFunction(()=>!document.getElementById('submitBtn').disabled);
            assert.match(await page.locator('#formError').textContent(),/quá nhiều/);
            assert.equal(new URL(page.url()).pathname,'/forgot-password');assert.equal(reachedDestination,0);
            assert.equal(await page.locator('#openResetCodeBtn').isVisible(),false);
            mode='network';await page.locator('#submitBtn').click();
            await page.waitForFunction(()=>!document.getElementById('submitBtn').disabled);
            assert.match(await page.locator('#formError').textContent(),/kết nối/);
            assert.equal(reachedDestination,0);
            mode='accepted';await page.locator('#submitBtn').click();
          } else {
            await page.waitForFunction(()=>document.getElementById('submitBtn').disabled);
            assert.equal(new URL(page.url()).pathname,'/forgot-password');assert.equal(reachedDestination,0);
            await page.evaluate(()=>document.getElementById('forgotForm').requestSubmit());
            assert.equal(calls.length,1);release();
          }
          if(scenario==='retained-document') {
            await page.waitForFunction(()=>document.getElementById('openResetCodeBtn')?.hidden===false,null,{timeout:3000});
            const link=page.locator('#openResetCodeBtn');
            await page.waitForFunction(()=>document.getElementById('formSuccess').style.display==='block');
            assert.equal(await link.getAttribute('href'),'/verify-reset-code?email=otp%2Btest%40example.invalid');
            await page.waitForFunction(()=>document.getElementById('submitBtn').disabled);
            await page.waitForTimeout(100);
            assert.equal(new URL(page.url()).pathname,'/forgot-password');
            assert.equal(await page.locator('#submitBtn').textContent(),'Đã gửi yêu cầu');
            assert.equal(await page.locator('#forgotForm').getAttribute('aria-busy'),null);
            assert.equal(await link.evaluate(el=>el.getBoundingClientRect().top>=document.getElementById('formSuccessMessage').getBoundingClientRect().bottom),true);
            assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
            await page.screenshot({path:path.join(output,`forgot-otp-fallback-${theme}-${width}-${motion}.png`),fullPage:true});
            await link.click();
          }
          await page.waitForURL('**/verify-reset-code?email=*',{timeout:3000});
          assert.equal(new URL(page.url()).searchParams.get('email'),'otp+test@example.invalid');
          assert.equal(calls.length,scenario==='rejected'?3:1);
          calls.forEach(body=>assert.deepEqual(body,{email:'otp+test@example.invalid'}));
          assert.deepEqual(errors,[]);result.passed=true;
        }catch(error){result.passed=false;result.error=error.message;}
        results.push(result);await context.close();
      }
    }
  }finally{await browser.close();}
  const report={baseline,configurations:results.length,passed:results.filter(r=>r.passed).length,failed:results.filter(r=>!r.passed).length,results};
  fs.writeFileSync(path.join(output,baseline?'before.json':'after.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));if(report.failed)process.exitCode=1;
})().catch(error=>{console.error(error);process.exitCode=1;});
