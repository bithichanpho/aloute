(function () {
  'use strict';
  const form=document.getElementById('verifyForm');
  if(!form||!window.Aloute||!window.AlouteAuthForms)return;
  const F=AlouteAuthForms,email=document.getElementById('email'),code=document.getElementById('code');
  const submit=document.getElementById('submitBtn'),resend=document.getElementById('resendBtn');
  const resendMessage=document.getElementById('resendMessage'),success=document.getElementById('verifySuccess');
  let busy=false,completed=false,cooldownUntil=0,timer,redirectTimer;
  code.addEventListener('input',()=>{code.value=code.value.replace(/\D/g,'').slice(0,6);});
  const feedback=F.create(form,document.getElementById('verifyError'),{
    email:{label:'Email đã đăng ký',validate:()=>F.emailError(email.value)},
    code:{label:'Mã OTP 6 chữ số',validate:()=>/^\d{6}$/.test(code.value.trim())?'':'Mã OTP gồm đúng 6 chữ số.'}
  });
  const otp=window.AlouteOtp?.create(code);
  if(otp){feedback.useInlineFeedback('code',otp.messageElement);feedback.setGeneralFeedbackTarget(otp.messageElement);otp.reset();}
  const fromUrl=new URLSearchParams(location.search).get('email')?.trim();if(fromUrl)email.value=fromUrl;
  F.focus(F.emailError(email.value)?email:code);
  function updateButtons(){
    const remaining=Math.max(0,Math.ceil((cooldownUntil-Date.now())/1000));
    submit.disabled=busy||completed;resend.disabled=busy||completed||remaining>0;
    resend.textContent=remaining?'Gửi lại mã OTP ('+remaining+'s)':'Gửi lại mã OTP';
    if(!remaining&&timer){clearInterval(timer);timer=null;}
  }
  function setBusy(value){busy=value;F.lock(form,value);updateButtons();}
  resend.addEventListener('click',async()=>{
    if(busy||completed||Date.now()<cooldownUntil)return;
    const invalid=F.emailError(email.value);if(invalid){feedback.show(invalid,'email');return;}
    resendMessage.textContent='';setBusy(true);
    try{
      await F.post('/api/auth/resend-verification',{identifier:email.value.trim()});
      resendMessage.textContent='Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email.';
      cooldownUntil=Date.now()+60000;clearInterval(timer);timer=setInterval(updateButtons,1000);
    }catch(error){resendMessage.textContent=error.message||'Không thể gửi lại mã OTP.';}
    finally{setBusy(false);}
  });
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||completed||!feedback.validate())return;
    setBusy(true);submit.textContent='Đang xác thực...';otp?.checking();
    try{
      await F.post('/api/auth/verify-email',{email:email.value.trim(),code:code.value.trim()});
      await otp?.settle();await otp?.success();
      completed=true;form.style.display='none';document.getElementById('resendBox').style.display='none';success.style.display='block';success.tabIndex=-1;F.focus(success);
      redirectTimer=setTimeout(()=>location.assign('/login'),2000);
    }catch(error){
      await otp?.settle();
      const message=error.message||'Xác thực thất bại. Vui lòng kiểm tra lại mã hoặc yêu cầu gửi lại mã OTP.';
      const wrongCode=message==='Mã xác thực không hợp lệ hoặc đã được dùng'||message==='Mã xác thực đã hết hạn, vui lòng yêu cầu gửi lại mã';
      if(!wrongCode)otp?.reset();
      feedback.show(message,wrongCode?'code':message.startsWith('Email không đúng định dạng')?'email':undefined);
      if(wrongCode)await otp?.invalid(message);
    }finally{if(!completed){setBusy(false);submit.textContent='Xác thực';}}
  });
  addEventListener('pageshow',event=>{if(event.persisted&&completed){clearTimeout(redirectTimer);completed=false;form.style.display='';document.getElementById('resendBox').style.display='';success.style.display='none';setBusy(false);otp?.reset();submit.textContent='Xác thực';}});
})();
