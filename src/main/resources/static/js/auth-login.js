(function () {
  'use strict';
  const form = document.getElementById('loginForm');
  if (!form || !window.Aloute || !window.AlouteAuthForms) return;
  const F = window.AlouteAuthForms;
  const username = document.getElementById('username');
  const password = document.getElementById('password');
  const submit = document.getElementById('submitBtn');
  const resend = document.getElementById('resendVerificationBtn');
  const resendBox = document.getElementById('resendVerificationBox');
  const resendMessage = document.getElementById('resendVerificationMessage');
  let busy = false;
  let completed = false;
  const feedback = F.create(form, document.getElementById('formError'), {
    username: { label: 'Tên đăng nhập hoặc email', validate: () => username.value.trim() ? '' : 'Vui lòng nhập tên đăng nhập hoặc email.' },
    password: { label: 'Mật khẩu', validate: () => password.value ? '' : 'Vui lòng nhập mật khẩu.' }
  });
  function setBusy(value) {
    busy = value;
    F.lock(form, value);
    submit.disabled = value;
    if (resend) resend.disabled = value;
  }
  function verificationLink(identifier) {
    const link = document.getElementById('openVerificationBtn');
    if (link) link.href = identifier.includes('@') ? '/verify-email?email=' + encodeURIComponent(identifier) : '/verify-email';
  }
  if (new URLSearchParams(location.search).get('registered')) {
    const notice = document.createElement('div');
    notice.className = 'form-success';
    notice.textContent = 'Đã ghi nhận đăng ký. Hãy kiểm tra email để lấy mã OTP rồi xác thực.';
    form.prepend(notice);
    if (resendBox) resendBox.style.display = 'block';
  }
  if (resend) resend.addEventListener('click', async () => {
    if (busy) return;
    const identifier = username.value.trim();
    if (!identifier) { feedback.show('Hãy nhập email hoặc tên đăng nhập.', 'username'); return; }
    verificationLink(identifier);
    if (resendMessage) resendMessage.textContent = '';
    setBusy(true);
    try {
      await F.post('/api/auth/resend-verification', { identifier });
      if (resendMessage) resendMessage.textContent = 'Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email.';
    } catch (error) {
      if (resendMessage) resendMessage.textContent = error.message || 'Không thể gửi lại mã OTP.';
    } finally { setBusy(false); }
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || completed || !feedback.validate()) return;
    setBusy(true);
    submit.textContent = 'Đang đăng nhập...';
    try {
      await F.post('/api/auth/login', { username: username.value.trim(), password: password.value });
      completed = true;
      location.assign('/feed');
    } catch (error) {
      const message = error.message || 'Đăng nhập thất bại, vui lòng thử lại.';
      if (message === 'Sai tên đăng nhập hoặc mật khẩu') {
        feedback.showGroup(message, ['username', 'password']);
      } else {
        feedback.show(message);
        if (message.toLocaleLowerCase('vi').includes('xác thực email') && resendBox) {
          resendBox.style.display = 'block';
          verificationLink(username.value.trim());
        }
      }
    } finally {
      if (!completed) { setBusy(false); submit.textContent = 'Đăng nhập'; }
    }
  });
  addEventListener('pageshow', event => {
    if (event.persisted && completed) { completed = false; setBusy(false); submit.textContent = 'Đăng nhập'; }
  });
})();
