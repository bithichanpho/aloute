(function () {
  'use strict';
  const form=document.getElementById('resetCodeForm');
  if(!form||!window.Aloute||!window.AlouteAuthForms)return;
  const F=AlouteAuthForms,email=document.getElementById('email'),code=document.getElementById('code');
  const submit=document.getElementById('submitBtn');let busy=false,completed=false;
  code.addEventListener('input',()=>{code.value=code.value.replace(/\D/g,'').slice(0,6);});
  const feedback=F.create(form,document.getElementById('formError'),{
    email:{label:'Email đã đăng ký',validate:()=>F.emailError(email.value)},
    code:{label:'Mã OTP 6 chữ số',validate:()=>/^\d{6}$/.test(code.value.trim())?'':'Mã OTP gồm đúng 6 chữ số.'}
  });
  const otp=window.AlouteOtp?.create(code);
  if(otp){feedback.useInlineFeedback('code',otp.messageElement);feedback.setGeneralFeedbackTarget(otp.messageElement);otp.reset();}
  const fromUrl=new URLSearchParams(location.search).get('email')?.trim();if(fromUrl)email.value=fromUrl;
  F.focus(F.emailError(email.value)?email:code);
  function setBusy(value){busy=value;F.lock(form,value);submit.disabled=value;}
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||completed||!feedback.validate())return;
    setBusy(true);submit.textContent='Đang xác thực...';otp?.checking();
    try{
      await F.post('/api/auth/verify-reset-code',{email:email.value.trim(),code:code.value.trim()});
      await otp?.settle();await otp?.success();completed=true;location.assign('/reset-password');
    }catch(error){
      await otp?.settle();const message=error.message||'Không thể xác thực mã. Vui lòng thử lại.';
      const wrongCode=message==='Mã đặt lại mật khẩu không hợp lệ hoặc đã được dùng'||message==='Mã đặt lại mật khẩu đã hết hạn, vui lòng yêu cầu lại';
      if(!wrongCode)otp?.reset();
      feedback.show(message,wrongCode?'code':undefined);if(wrongCode)await otp?.invalid(message);
    }finally{if(!completed){setBusy(false);submit.textContent='Xác thực mã OTP';}}
  });
  addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
})();
