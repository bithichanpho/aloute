const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.ALOUTE_PLAYWRIGHT_MODULE);

(async()=>{
  const output=process.env.ALOUTE_AUTH_EVIDENCE;assert.ok(output);fs.mkdirSync(output,{recursive:true});
  const before=process.argv.includes('before'), results=[];
  const root=path.resolve('src/main/resources/static');
  const browser=await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  try {
    for(const theme of ['dark','light'])for(const width of [1280,320])for(const motion of ['no-preference','reduce'])for(const kind of ['verify','verify-reset-code']) {
      if(before&&(theme!=='dark'||width!==1280||motion!=='reduce'))continue;
      const context=await browser.newContext({viewport:{width,height:900},reducedMotion:motion});
      await context.addInitScript(theme=>localStorage.setItem('aloute-theme',theme),theme);
      await context.addCookies([{name:'XSRF-TOKEN',value:'fixture-csrf',url:'http://aloute.test'}]);
      const page=await context.newPage(),calls=[],errors=[];
      page.on('pageerror',error=>errors.push(error.message));
      const entry=kind==='verify'?'/verify-email':'/verify-reset-code';
      const endpoint=kind==='verify'?'/api/auth/verify-email':'/api/auth/verify-reset-code';
      const source=fs.readFileSync(`src/main/resources/templates/auth/${kind}.html`,'utf8')
        .replace(/\bth:(src|href)="@\{([^}]+)\}"/g,'$1="$2"').replace(/\s+th:value="[^"]*"/g,'');
      await page.route('**/*',async route=>{
        const url=new URL(route.request().url());if(url.hostname!=='aloute.test')return route.abort();
        if(url.pathname===entry)return route.fulfill({contentType:'text/html; charset=utf-8',body:source});
        if(['/reset-password','/login'].includes(url.pathname))return route.fulfill({contentType:'text/html',body:'OTP success destination fixture'});
        if(url.pathname===endpoint) {
          assert.equal(route.request().headers()['x-xsrf-token'],'fixture-csrf');
          const body=route.request().postDataJSON();calls.push(body);
          const accepted=body.email==='otp@example.invalid'&&body.code==='012345';
          return route.fulfill({status:accepted?200:400,contentType:'application/json',body:JSON.stringify({success:accepted,data:null,
            message:kind==='verify'?'Mã xác thực không hợp lệ hoặc đã được dùng':'Mã đặt lại mật khẩu không hợp lệ hoặc đã được dùng'})});
        }
        if(url.pathname.startsWith('/api/'))return route.fulfill({contentType:'application/json',body:'{"success":true,"data":{}}'});
        const file=path.resolve(root,'.'+url.pathname);
        if(!file.startsWith(root+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:''});
        return route.fulfill({contentType:file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'application/octet-stream',body:fs.readFileSync(file)});
      });
      const result={kind,theme,width,motion};
      try {
        await page.goto('http://aloute.test'+entry+'?email=otp%40example.invalid');
        const code=page.locator('#code'),submit=page.locator('#submitBtn');
        const selection=()=>code.evaluate(el=>[el.selectionStart,el.selectionEnd]);
        const paste=text=>code.evaluate((el,text)=>{const data=new DataTransfer();data.setData('text/plain',text);el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));},text);
        await code.fill('0145');await code.evaluate(el=>el.setSelectionRange(2,2));await code.pressSequentially('a');
        assert.equal(await code.inputValue(),'0145');assert.deepEqual(await selection(),[2,2],'Ignoring a letter must preserve the editing position');
        await code.pressSequentially('23');assert.equal(await code.inputValue(),'012345');
        await code.evaluate(el=>el.setSelectionRange(1,4));await code.pressSequentially('b');
        assert.equal(await code.inputValue(),'012345','A rejected letter must not delete selected OTP digits');
        assert.deepEqual(await selection(),[1,4]);
        await paste('letters only');assert.equal(await code.inputValue(),'012345');assert.deepEqual(await selection(),[1,4]);
        assert.equal(calls.length,0,'Typing or pasting must never auto-submit');
        // Autofill can deliver an input event without a cancellable beforeinput event.
        await code.evaluate(el=>{el.value='01x45';el.setSelectionRange(3,3);el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'x'}));});
        assert.equal(await code.inputValue(),'0145');assert.deepEqual(await selection(),[2,2]);
        await code.pressSequentially('23');assert.equal(await code.inputValue(),'012345');
        // Clear the previous valid code first: rejecting letters intentionally preserves it.
        await code.fill('');await code.fill('ab');await submit.click();
        assert.equal(calls.length,0,'Malformed input must not consume a server attempt');
        await code.fill('000000');await submit.click();
        await page.waitForFunction(()=>document.getElementById('code').value===''&&!document.getElementById('submitBtn').disabled);
        assert.equal(calls.length,1);assert.equal(await code.getAttribute('aria-invalid'),'true');
        await code.pressSequentially('abc');assert.equal(await code.inputValue(),'');assert.equal(calls.length,1);
        await code.pressSequentially('012345');
        assert.equal(await code.getAttribute('aria-invalid'),'false','Editing must clear the previous server failure');
        assert.equal(await page.locator('.u026.is-invalid').count(),0);
        assert.equal(await page.locator(kind==='verify'?'#verifyError':'#formError').isVisible(),false);
        await submit.click();await page.waitForURL(kind==='verify'?'**/login':'**/reset-password');
        assert.deepEqual(calls,[{email:'otp@example.invalid',code:'000000'},{email:'otp@example.invalid',code:'012345'}]);
        assert.deepEqual(errors,[]);result.passed=true;
      }catch(error){result.passed=false;result.error=error.message;}
      results.push(result);await context.close();
    }
  }finally{await browser.close();}
  const report={before,configurations:results.length,passed:results.filter(r=>r.passed).length,failed:results.filter(r=>!r.passed).length,results};
  fs.writeFileSync(path.join(output,before?'input-retry-before.json':'input-retry-after.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));if(report.failed)process.exitCode=1;
})().catch(error=>{console.error(error);process.exitCode=1;});
