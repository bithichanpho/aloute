const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async()=>{
  const before=process.argv.includes('before'), reliability=process.argv.includes('reliability'), otpMotion=process.argv.includes('otp'), output=process.env.ALOUTE_AUTH_EVIDENCE;
  assert.ok(output);fs.mkdirSync(output,{recursive:true});
  const backup=process.env.ALOUTE_AUTH_BACKUP, root=path.resolve('src/main/resources/static');
  const browser=await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  const results=[];
  try{
    const kinds=before?['login','verify','forgot-password','reset-password']:['login','verify','forgot-password','verify-reset-code'];
    for(const theme of ['dark','light'])for(const width of [1280,320])for(const motion of ['no-preference','reduce'])for(const kind of kinds){
      if((before||reliability)&&(theme!=='dark'||width!==1280||motion!=='no-preference'))continue;
      if(otpMotion&&kind==='forgot-password')continue;
      const context=await browser.newContext({viewport:{width,height:width===320?640:900},reducedMotion:motion});
      await context.addInitScript(theme=>localStorage.setItem('aloute-theme',theme),theme);
      // Accelerate only the request deadline. Redirect and UI timers retain their real delays.
      if(reliability)await context.addInitScript(()=>{const schedule=window.setTimeout;window.setTimeout=(fn,delay,...args)=>schedule(fn,delay===30000?180:delay,...args);});
      await context.addCookies([{name:'XSRF-TOKEN',value:'test-xsrf',url:'http://aloute.test'}]);
      const page=await context.newPage(), errors=[], calls=[];
      page.on('pageerror',e=>errors.push(e.message));
      page.on('console',m=>{if(m.type()==='error'&&/content security policy/i.test(m.text()))errors.push(m.text());});
      let mode='wrong', resendCount=0, release;
      const held=new Promise(resolve=>release=resolve);
      const filename=kind==='verify'?'verify':kind;
      const source=fs.readFileSync(before?path.join(backup,`src/main/resources/templates/auth/${filename}.html`):`src/main/resources/templates/auth/${filename}.html`,'utf8')
        .replace(/\bth:(src|href)="@\{([^}]+)\}"/g,'$1="$2"').replace(/\s+th:value="[^"]*"/g,'');
      const entry=kind==='verify'?'/verify-email':'/'+kind;
      await page.route('**/*',async route=>{
        const u=new URL(route.request().url());if(u.hostname!=='aloute.test')return route.abort();
        if(u.pathname===entry)return route.fulfill({contentType:'text/html; charset=utf-8',body:source,
          headers:{'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'"}});
        if(['/feed','/login','/reset-password','/verify-reset-code'].includes(u.pathname))return route.fulfill({contentType:'text/html',body:'<!doctype html><p>Destination fixture</p>'});
        if(u.pathname.startsWith('/api/auth/')){
          const body=route.request().postDataJSON();assert.equal(route.request().headers()['x-xsrf-token'],'test-xsrf');calls.push({url:u.pathname,body});
          if(mode==='hold') {await held;return route.fulfill({contentType:'application/json',body:'{"success":true,"data":null}'}).catch(()=>{});}
          if(mode==='network')return route.abort('failed');
          if(u.pathname.endsWith('resend-verification')){resendCount++;return route.fulfill({contentType:'application/json',body:'{"success":true,"message":"Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email","data":null}'});}
          let status=mode==='accepted'?200:mode==='rate'?429:kind==='login'?401:400;
          let message=mode==='rate'?'Bạn đã thử quá nhiều lần. Vui lòng thử lại sau.':kind==='login'
            ? mode==='challenge'?'Vui lòng nhập mã xác thực hai lớp hoặc mã khôi phục':mode==='mfa'?'Cần mã 2FA hợp lệ hoặc mã khôi phục chưa sử dụng':'Sai tên đăng nhập hoặc mật khẩu'
            :kind==='verify' ? mode==='expired'?'Mã xác thực đã hết hạn, vui lòng yêu cầu gửi lại mã':'Mã xác thực không hợp lệ hoặc đã được dùng'
            :kind==='verify-reset-code' ? mode==='expired'?'Mã đặt lại mật khẩu đã hết hạn, vui lòng yêu cầu lại':'Mã đặt lại mật khẩu không hợp lệ hoặc đã được dùng'
            :'Email không đúng định dạng';
          return route.fulfill({status,contentType:'application/json',body:JSON.stringify({success:status===200,message:status===200?'Nếu thông tin hợp lệ, yêu cầu đã được ghi nhận':message,data:null})});
        }
        if(u.pathname.startsWith('/api/'))return route.fulfill({contentType:'application/json',body:'{"success":true,"data":{}}'});
        const file=path.resolve(root,'.'+u.pathname);if(!file.startsWith(root+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:''});
        const original=before&&u.pathname===`/js/auth-${kind}.js`?path.join(backup,`src/main/resources/static/js/auth-${kind}.js`):file;
        return route.fulfill({contentType:file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'application/octet-stream',body:fs.readFileSync(original)});
      });
      await page.goto('http://aloute.test'+entry+(['verify','verify-reset-code'].includes(kind)?'?email=focus-test%40example.invalid':''));
      const submit=page.locator('#submitBtn');
      const active=()=>page.evaluate(()=>document.activeElement.id);
      const focused=async id=>{await page.waitForFunction(id=>document.activeElement.id===id,id);assert.equal(await active(),id);};
      const otpCleared=async id=>{
        await page.waitForFunction(id=>document.getElementById(id).value===''&&!document.getElementById('submitBtn').disabled,id);
        await focused(id);
      };
      const singleOtpMessage=async(id,expected)=>{
        const summary=page.locator(kind==='verify'?'#verifyError':'#formError');
        assert.equal(await summary.isVisible(),false);
        assert.equal(await summary.textContent(),'');
        assert.equal(await page.locator('#'+id+'Feedback').textContent(),'');
        assert.equal(await page.locator('#'+id+'Feedback').locator('..').locator('..').isVisible(),false);
        const status=page.locator('#'+id+'OtpStatus');
        assert.match(await status.textContent(),expected);
        assert.ok((await page.locator('#'+id).getAttribute('aria-describedby')).split(/\s+/).includes(id+'OtpStatus'));
        assert.equal(await status.getAttribute('role'),'status');
        assert.equal(await status.evaluate(el=>{const probe=document.createElement('span');probe.style.color='var(--text-secondary)';el.appendChild(probe);const same=getComputedStyle(el).color===getComputedStyle(probe).color;probe.remove();return same;}),true);
      };
      if(otpMotion){
        const id=kind==='login'?'twoFactorCode':'code', code=page.locator('#'+id);
        if(kind==='login'){
          await page.locator('#username').fill('focus-user');await page.locator('#password').fill('legacy');mode='challenge';await submit.click();await focused(id);
        }
        if(kind==='verify-reset-code')assert.equal(await page.locator('input[type="password"]').count(),0);
        const root=page.locator('.u026');
        assert.equal(await root.count(),1);assert.equal(await root.locator('.u026-c').count(),6);
        const successColor=await root.evaluate(el=>{const probe=document.createElement('span');probe.style.color='var(--ux-mint)';el.appendChild(probe);const color=getComputedStyle(probe).color;probe.remove();return color;});
        assert.equal(successColor,theme==='dark'?'rgb(126, 232, 192)':'rgb(17, 156, 114)');
        assert.equal(await code.getAttribute('name'),'code');assert.equal(await code.getAttribute('autocomplete'),'one-time-code');
        assert.equal(await page.locator('label[for="'+id+'"]').count(),1);
        const paste=async text=>code.evaluate((input,text)=>{const data=new DataTransfer();data.setData('text/plain',text);input.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));},text);
        const callsBefore=calls.length;
        await code.focus();await code.pressSequentially('123',{delay:30});
        assert.equal(await root.locator('.u026-c.has').count(),3);
        const digitAnimation=await root.locator('.u026-c span').first().evaluate(el=>getComputedStyle(el).animationName);
        assert.equal(digitAnimation,motion==='reduce'?'none':'u026-in');
        await page.keyboard.press('Backspace');assert.equal(await code.inputValue(),'12');
        await page.keyboard.press('ArrowLeft');await page.keyboard.press('9');assert.equal(await code.inputValue(),'192');
        await code.selectText();await paste('482 915');assert.equal(await code.inputValue(),'482915');
        assert.equal(await root.locator('.u026-c.has').count(),6);assert.equal(calls.length,callsBefore);
        assert.equal(await root.evaluate(el=>el.classList.contains('is-check')||el.classList.contains('is-ok')),false);
        await page.screenshot({path:path.join(output,`otp-filled-${kind}-${theme}-${width}-${motion}.png`),fullPage:true,animations:'disabled'});
        mode=kind==='login'?'mfa':'wrong';await submit.click();
        await page.waitForFunction(()=>document.querySelector('.u026').classList.contains('is-bad'));
        assert.equal(await code.inputValue(),'482915');
        await singleOtpMessage(id,kind==='login'?/Cần mã 2FA/:/không hợp lệ hoặc đã được dùng/);
        assert.equal(await root.locator('.u026-row').evaluate(el=>getComputedStyle(el).animationName),motion==='reduce'?'none':'u026-shake');
        await otpCleared(id);assert.equal(await root.locator('.u026-c.has').count(),0);
        assert.equal(await code.getAttribute('aria-invalid'),'true');
        if(kind==='login'){
          await root.locator('.u026-toggle').click();await code.fill('RECOVERY-TEST-CODE');
          assert.equal(await code.getAttribute('inputmode'),'text');assert.equal(await code.getAttribute('maxlength'),'64');
          assert.equal(await code.evaluate(el=>getComputedStyle(el).opacity),'1');
          await submit.click();await page.waitForFunction(()=>!document.getElementById('submitBtn').disabled);
          assert.equal(await code.inputValue(),'RECOVERY-TEST-CODE');assert.equal(calls.at(-1).body.code,'RECOVERY-TEST-CODE');
          await root.locator('.u026-toggle').click();assert.equal(await code.inputValue(),'');
        }
        await code.selectText();await paste('027361');mode='hold';
        const formId=kind==='login'?'loginForm':kind==='verify'?'verifyForm':'resetCodeForm';
        const beforeHeld=calls.length;
        await page.evaluate(id=>{document.getElementById(id).requestSubmit();document.getElementById(id).requestSubmit();},formId);
        await page.waitForFunction(()=>document.querySelector('.u026').classList.contains('is-check'));
        assert.equal(await code.evaluate(el=>el.readOnly),true);
        await page.waitForTimeout(700);
        assert.equal(calls.length,beforeHeld+1);assert.equal(await root.evaluate(el=>el.classList.contains('is-ok')),false);
        assert.equal(await root.getAttribute('aria-busy'),'true');
        assert.equal(await root.locator('.u026-c').first().evaluate(el=>getComputedStyle(el).animationName),motion==='reduce'?'none':'u026-wave');
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
        release();
        if(motion!=='reduce'){
          await page.waitForFunction(()=>document.querySelector('.u026').classList.contains('is-ok'));
          await page.screenshot({path:path.join(output,`otp-success-${kind}-${theme}-${width}.png`),fullPage:true,animations:'disabled'});
        }
        await page.waitForURL(kind==='login'?'**/feed':kind==='verify-reset-code'?'**/reset-password':'**/login');
        assert.equal(calls.at(-1).body.code,'027361');assert.deepEqual(errors,[]);
        results.push({kind,theme,width,motion,pasteAndKeyboard:true,serverAuthoritative:true,invalidClears:true,singleFlight:true,recoveryPreserved:kind==='login',errors});
        await context.close();continue;
      }
      const snapshot=async (id,grouped=false)=>{
        await page.waitForTimeout(550);
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
        const field=page.locator('#'+id), hint=grouped?'formError':['code','twoFactorCode'].includes(id)?id+'OtpStatus':id+'Feedback';
        assert.ok((await field.getAttribute('aria-describedby')).split(/\s+/).includes(hint));
        assert.ok((await page.locator('#'+hint).textContent()).length>0);
        assert.equal(await field.evaluate(el=>{const r=el.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),true);
        await page.screenshot({path:path.join(output,`${kind}-${theme}-${width}-${motion}.png`),fullPage:true});
      };
      if(reliability){
        const formId={login:'loginForm',verify:'verifyForm','forgot-password':'forgotForm','verify-reset-code':'resetCodeForm'}[kind];
        if(kind==='login'){await page.locator('#username').fill('focus-user');await page.locator('#password').fill('legacy');}
        else await page.locator('#email').fill('focus-test@example.invalid');
        if(['verify','verify-reset-code'].includes(kind))await page.locator('#code').fill('123456');
        mode='hold';
        await page.evaluate(id=>{document.getElementById(id).requestSubmit();document.getElementById(id).requestSubmit();},formId);
        assert.equal(await submit.isDisabled(),true);
        assert.equal(await page.locator('#'+formId+' input').evaluateAll(inputs=>inputs.every(input=>input.readOnly)),true);
        const errorId=['verify','verify-reset-code'].includes(kind)?'codeOtpStatus':'formError';
        await focused(errorId);assert.match(await page.locator('#'+errorId).textContent(),/chưa xác định được kết quả/);
        assert.equal(calls.length,1);assert.equal(await submit.isDisabled(),false);
        assert.equal(await page.locator('[aria-invalid="true"]').count(),0);
        assert.equal(await page.locator('#'+formId+' input').evaluateAll(inputs=>inputs.every(input=>!input.readOnly)),true);
        release();await page.waitForTimeout(50);assert.equal(new URL(page.url()).pathname,entry);
        mode='network';await submit.click();await focused(errorId);
        assert.equal(await page.locator('[aria-invalid="true"]').count(),0);assert.equal(await submit.isDisabled(),false);
        mode='accepted';await submit.click();
        await page.waitForURL(kind==='login'?'**/feed':kind==='forgot-password'?'**/verify-reset-code?email=*':kind==='verify-reset-code'?'**/reset-password':'**/login');
        assert.equal(calls.length,3);assert.deepEqual(errors,[]);
        results.push({kind,singleFlight:true,readonly:true,timeoutAndNetworkRetry:true,lateResponseIgnored:true,deadlineAccelerated:true});
        await context.close();continue;
      }
      if(before){
        if(kind==='login'){await page.locator('#username').fill('focus-user');await page.locator('#password').fill('wrong');}
        if(kind==='forgot-password')await page.locator('#email').fill('wrong');
        if(kind==='verify')await page.locator('#code').fill('000000');
        if(kind==='reset-password'){await page.locator('#code').fill('000000');await page.locator('#newPassword').fill('Test-Password1!');await page.locator('#confirmPassword').fill('Test-Password1!');}
        await submit.click();await page.waitForTimeout(200);
        results.push({kind,before,active:await active(),invalidFields:await page.locator('[aria-invalid="true"]').count()});await context.close();continue;
      }
      if(kind==='login'){
        await submit.click();await focused('username');assert.equal(calls.length,0);
        await page.locator('#username').fill('focus-user');await page.locator('#password').fill('x');await submit.click();await focused('username');
        assert.equal(await page.locator('#username').inputValue(),'focus-user');assert.equal(new URL(page.url()).pathname,'/login');
        assert.equal(await page.locator('#password').getAttribute('aria-invalid'),'true');
        assert.equal(await page.locator('#username').getAttribute('aria-invalid'),'true');
        assert.equal(await page.locator('#formError').textContent(),'Sai tên đăng nhập hoặc mật khẩu');
        assert.equal(await page.locator('#formError a, #formError li').count(),0);
        assert.equal(await page.locator('#usernameFeedback, #passwordFeedback').allTextContents().then(items=>items.join('')),'');
        await snapshot('username',true);
        await page.keyboard.press('Tab');await focused('password');
        await page.locator('#password').fill('another-wrong');
        assert.equal(await page.locator('[aria-invalid="true"]').count(),0);
        assert.equal(await page.locator('#formError').isVisible(),false);
        assert.equal(await page.locator('#username').getAttribute('aria-describedby'),null);
        await submit.click();await focused('username');
        await page.locator('#username').fill('another-user');
        assert.equal(await page.locator('[aria-invalid="true"]').count(),0);
        assert.equal(await page.locator('#password').getAttribute('aria-describedby'),null);
        mode='challenge';await page.locator('#password').fill('legacy');await submit.click();await focused('twoFactorCode');
        assert.equal(await page.locator('#twoFactorStep').isVisible(),true);assert.equal(await page.locator('#formError').isVisible(),false);
        mode='mfa';await page.locator('#twoFactorCode').fill('000000');await submit.click();await focused('twoFactorCode');
        await otpCleared('twoFactorCode');
        await singleOtpMessage('twoFactorCode',/Cần mã 2FA/);
        assert.equal(await page.locator('#twoFactorCode').getAttribute('aria-invalid'),'true');
        assert.deepEqual(await page.locator('#twoFactorCode').evaluate(el=>[el.selectionStart,el.selectionEnd]),[0,0]);
        await page.locator('#twoFactorCode').fill('000000');
        mode='rate';await submit.click();await focused('twoFactorCodeOtpStatus');await singleOtpMessage('twoFactorCode',/quá nhiều/);assert.equal(await page.locator('#twoFactorCode').inputValue(),'000000');assert.notEqual(await page.locator('#twoFactorCode').getAttribute('aria-invalid'),'true');
        mode='wrong';await submit.click();await focused('username');assert.equal(await page.locator('#credentialsStep').isVisible(),true);
        assert.equal(await page.locator('#password').getAttribute('aria-invalid'),'true');
        assert.equal(await page.locator('#username').getAttribute('aria-invalid'),'true');
        assert.equal(await page.locator('#twoFactorCode').inputValue(),'');
        mode='challenge';await submit.click();await focused('twoFactorCode');
        await page.locator('#backToLoginBtn').click();await focused('username');assert.equal(await page.locator('#twoFactorCode').inputValue(),'');
        await submit.click();await focused('twoFactorCode');
        mode='accepted';await page.locator('#twoFactorCode').fill('RECOVERY-TEST-CODE');await submit.click();await page.waitForURL('**/feed');
        assert.equal(calls.at(-1).body.code,'RECOVERY-TEST-CODE');assert.ok(!calls.some(call=>call.url.includes('forgot')||call.url.includes('verify-email')));
      }else if(kind==='forgot-password'){
        await submit.click();await focused('email');assert.equal(calls.length,0);
        await page.locator('#email').fill('wrong');await submit.click();await focused('email');assert.equal(calls.length,0);
        await page.locator('#email').fill('focus-test@example.invalid');await submit.click();await focused('email');
        await page.locator('#formError a[href="#email"]').focus();await page.keyboard.press('Enter');await focused('email');
        await snapshot('email');
        mode='rate';await submit.click();await focused('formError');assert.notEqual(await page.locator('#email').getAttribute('aria-invalid'),'true');
        mode='accepted';await page.evaluate(()=>{document.getElementById('forgotForm').requestSubmit();document.getElementById('forgotForm').requestSubmit();});
        await page.waitForURL('**/verify-reset-code?email=*');assert.equal(new URL(page.url()).searchParams.get('email'),'focus-test@example.invalid');
        assert.equal(calls.length,3);
      }else{
        await focused('code');await submit.click();await focused('code');assert.equal(calls.length,0);
        await page.locator('#code').fill('123456');
        if(kind==='verify-reset-code')assert.equal(await page.locator('input[type="password"]').count(),0);
        await submit.click();await focused('code');assert.equal(await page.locator('#code').getAttribute('aria-invalid'),'true');
        await otpCleared('code');
        assert.deepEqual(await page.locator('#code').evaluate(el=>[el.selectionStart,el.selectionEnd]),[0,0]);
        await singleOtpMessage('code',/không hợp lệ hoặc đã được dùng/);
        await snapshot('code');
        if(motion==='reduce')assert.equal(await page.locator('#code').evaluate(el=>getComputedStyle(el).animationName),'none');
        mode='expired';await page.locator('#code').fill('123456');await submit.click();await otpCleared('code');
        await singleOtpMessage('code',/hết hạn/);
        mode='rate';await page.locator('#code').fill('123456');await submit.click();await focused('codeOtpStatus');await singleOtpMessage('code',/quá nhiều/);assert.equal(await page.locator('#code').inputValue(),'123456');assert.notEqual(await page.locator('#code').getAttribute('aria-invalid'),'true');
        if(kind==='verify'){
          await page.locator('#resendBtn').click();await page.waitForFunction(()=>document.getElementById('resendMessage').textContent.startsWith('Nếu tài khoản tồn tại'));
          assert.equal(await page.locator('#resendBtn').isDisabled(),true);assert.equal(resendCount,1);
          mode='wrong';await submit.click();await otpCleared('code');assert.equal(await page.locator('#resendBtn').isDisabled(),true);
        }
        mode='accepted';await page.locator('#code').fill('123456');await submit.click();await page.waitForURL(kind==='verify-reset-code'?'**/reset-password':'**/login');
        const call=calls.findLast(call=>call.url.endsWith(kind==='verify'?'verify-email':'verify-reset-code'));
        assert.equal(call.body.code,'123456');assert.equal(call.body.email,'focus-test@example.invalid');
        if(kind==='verify-reset-code')assert.deepEqual(Object.keys(call.body).sort(),['code','email']);
      }
      assert.deepEqual(errors,[]);
      results.push({kind,theme,width,motion,focusAndRetry:true,keyboard:true,contractsAndCsrf:true,errors});await context.close();
    }
    fs.writeFileSync(path.join(output,before?'before.json':reliability?'reliability.json':otpMotion?'otp.json':'after.json'),JSON.stringify({before,reliability,otpMotion,configurations:results.length,results},null,2));
    console.log(JSON.stringify({before,reliability,otpMotion,configurations:results.length,results}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
