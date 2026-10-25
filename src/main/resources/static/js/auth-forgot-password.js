(function () {
  'use strict';
  const form=document.getElementById('forgotForm');
  if(!form||!window.Aloute||!window.AlouteAuthForms)return;
  const F=AlouteAuthForms,email=document.getElementById('email'),submit=document.getElementById('submitBtn');
  const success=document.getElementById('formSuccess'),successMessage=document.getElementById('formSuccessMessage');
  const openCode=document.getElementById('openResetCodeBtn');let busy=false,completed=false;
  const feedback=F.create(form,document.getElementById('formError'),{email:{label:'Email đã đăng ký',validate:()=>F.emailError(email.value)}});
  function setBusy(value){busy=value;F.lock(form,value);submit.disabled=value;}
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||completed)return;success.style.display='none';openCode.hidden=true;if(!feedback.validate())return;
    const value=email.value.trim();setBusy(true);submit.textContent='Đang gửi...';
    try{
      await F.post('/api/auth/forgot-password',{email:value});
      completed=true;
      busy=false;form.removeAttribute('aria-busy');submit.textContent='Đã gửi yêu cầu';
      const destination='/verify-reset-code?email='+encodeURIComponent(value);
      openCode.href=destination;openCode.hidden=false;
      successMessage.textContent='Nếu email tồn tại trong hệ thống, mã OTP đặt lại mật khẩu đã được gửi tới hộp thư của bạn. Nếu chưa chuyển trang, chọn Nhập mã OTP.';
      success.style.display='block';
      // Do not depend on a delayed timer or scrolling to reach the OTP step.
      // The link remains usable if the browser retains this document after navigation.
      try{location.assign(destination);}
      catch(_){successMessage.textContent='Yêu cầu đã được ghi nhận. Trình duyệt chưa chuyển trang; vui lòng chọn Nhập mã OTP.';F.focus(openCode);}
    }catch(error){const message=error.message||'Gửi yêu cầu thất bại, vui lòng thử lại.';feedback.show(message,message.startsWith('Email không đúng định dạng')?'email':undefined);}
    finally{if(!completed){setBusy(false);submit.textContent='Gửi mã OTP';}}
  });
  addEventListener('pageshow',event=>{if(event.persisted&&completed){completed=false;setBusy(false);success.style.display='none';openCode.hidden=true;submit.textContent='Gửi mã OTP';}});
})();
