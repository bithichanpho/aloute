(function () {
  'use strict';
  const form=document.getElementById('resetForm');
  if(!form||!window.Aloute||!window.AlouteAuthForms)return;
  const F=AlouteAuthForms,password=document.getElementById('newPassword'),confirm=document.getElementById('confirmPassword');
  const submit=document.getElementById('submitBtn'),success=document.getElementById('formSuccess');let busy=false,completed=false;
  const feedback=F.create(form,document.getElementById('formError'),{
    newPassword:{label:'Mật khẩu mới',validate:()=>F.passwordError(password.value)},
    confirmPassword:{label:'Xác nhận mật khẩu mới',validate:()=>!confirm.value?'Vui lòng xác nhận mật khẩu mới.':password.value===confirm.value?'':'Hai mật khẩu nhập vào không khớp.'}
  });
  F.focus(password);
  function setBusy(value){busy=value;F.lock(form,value);submit.disabled=value;}
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||completed)return;success.style.display='none';if(!feedback.validate())return;
    setBusy(true);submit.textContent='Đang xử lý...';
    try{
      await F.post('/api/auth/complete-password-reset',{newPassword:password.value});
      completed=true;success.textContent='Đặt lại mật khẩu thành công! Đang chuyển tới trang đăng nhập...';success.style.display='block';form.style.display='none';success.tabIndex=-1;F.focus(success);
      setTimeout(()=>location.assign('/login'),1800);
    }catch(error){
      const message=error.message||'Đặt lại mật khẩu thất bại, vui lòng thử lại.';
      if(message.startsWith('Phiên đặt lại')||message.startsWith('Mã đặt lại'))form.style.display='none';
      feedback.show(message,message.startsWith('Mật khẩu')?'newPassword':undefined);
    }finally{if(!completed){setBusy(false);submit.textContent='Đặt lại mật khẩu';}}
  });
  addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
})();
