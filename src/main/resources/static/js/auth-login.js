(function () {
  'use strict';
  const form=document.getElementById('loginForm');
  if(!form || !window.Aloute || !window.AlouteAuthForms)return;
  const F=AlouteAuthForms, username=document.getElementById('username'), password=document.getElementById('password');
  const code=document.getElementById('twoFactorCode'), submit=document.getElementById('submitBtn');
  const back=document.getElementById('backToLoginBtn'), credentials=document.getElementById('credentialsStep');
  const twoFactor=document.getElementById('twoFactorStep'), resend=document.getElementById('resendVerificationBtn');
  const resendBox=document.getElementById('resendVerificationBox'), resendMessage=document.getElementById('resendVerificationMessage');
  let awaitingTwoFactor=false, busy=false, completed=false;
  const feedback=F.create(form,document.getElementById('formError'),{
    username:{label:'Tên đăng nhập hoặc email',validate:()=>username.value.trim()?'':'Vui lòng nhập tên đăng nhập hoặc email.'},
    password:{label:'Mật khẩu',validate:()=>password.value?'':'Vui lòng nhập mật khẩu.'},
    twoFactorCode:{label:'Mã xác thực hoặc mã khôi phục',validate:()=>awaitingTwoFactor&&!code.value.trim()?'Vui lòng nhập mã xác thực hoặc mã khôi phục.':''}
  });
  const otp=window.AlouteOtp?.create(code,{allowRecovery:true});
  if(otp){feedback.useInlineFeedback('twoFactorCode',otp.messageElement);otp.reset();}
  function buttonLabel(){return awaitingTwoFactor?'Xác nhận và đăng nhập':'Đăng nhập';}
  function setBusy(value){busy=value; F.lock(form,value); submit.disabled=value; back.disabled=value; resend.disabled=value;}
  function credentialsView(){awaitingTwoFactor=false; feedback.setGeneralFeedbackTarget(null); code.value=''; otp?.reset(); twoFactor.style.display='none'; credentials.style.display='block'; back.style.display='none'; submit.textContent=buttonLabel();}
  function verificationLink(identifier){document.getElementById('openVerificationBtn').href=identifier.includes('@')?'/verify-email?email='+encodeURIComponent(identifier):'/verify-email';}
  back.addEventListener('click',()=>{if(busy)return; credentialsView();code.value='';feedback.clear();F.focus(username);});
  if(new URLSearchParams(location.search).get('registered')){
    const notice=document.createElement('div');notice.className='form-success';notice.textContent='Đã ghi nhận đăng ký. Hãy kiểm tra email để lấy mã OTP rồi xác thực.';form.prepend(notice);resendBox.style.display='block';
  }
  resend.addEventListener('click',async()=>{
    if(busy)return;
    const identifier=username.value.trim();
    if(!identifier){feedback.show('Hãy nhập email hoặc tên đăng nhập.','username');return;}
    verificationLink(identifier);resendMessage.textContent='';setBusy(true);
    try{await F.post('/api/auth/resend-verification',{identifier});resendMessage.textContent='Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email.';}
    catch(error){resendMessage.textContent=error.message||'Không thể gửi lại mã OTP.';}
    finally{setBusy(false);}
  });
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||completed)return;
    if(!feedback.validate())return;
    setBusy(true);submit.textContent=awaitingTwoFactor?'Đang xác nhận...':'Đang đăng nhập...';
    if(awaitingTwoFactor)otp?.checking();
    try{
      await F.post('/api/auth/login',{username:username.value.trim(),password:password.value,code:code.value.trim()});
      if(awaitingTwoFactor){await otp?.settle();await otp?.success();}
      completed=true;location.assign('/feed');
    }catch(error){
      if(awaitingTwoFactor)await otp?.settle();
      const message=error.message||'Đăng nhập thất bại, vui lòng thử lại.';
      if(message==='Vui lòng nhập mã xác thực hai lớp hoặc mã khôi phục'){
        awaitingTwoFactor=true;credentials.style.display='none';twoFactor.style.display='block';back.style.display='block';feedback.clear();otp?.reset();feedback.setGeneralFeedbackTarget(otp?.messageElement);F.focus(code);
      }else if(message==='Sai tên đăng nhập hoặc mật khẩu'){
        // Treat the credential pair as one failure without singling out either field.
        credentialsView();feedback.showGroup(message,['username','password']);
      }else if(awaitingTwoFactor && message==='Cần mã 2FA hợp lệ hoặc mã khôi phục chưa sử dụng'){
        feedback.show(message,'twoFactorCode');
        await otp?.invalid(message);
      }else{
        otp?.reset();
        feedback.show(message);
        if(message.toLowerCase().includes('xác thực email')){resendBox.style.display='block';verificationLink(username.value.trim());}
      }
    }finally{if(!completed){setBusy(false);submit.textContent=buttonLabel();}}
  });
  addEventListener('pageshow',event=>{if(event.persisted&&completed){completed=false;setBusy(false);otp?.reset();submit.textContent=buttonLabel();}});
})();
