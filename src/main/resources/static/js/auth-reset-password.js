(function () {
  const form = document.getElementById('resetForm');
  const emailInput = document.getElementById('email');
  const codeInput = document.getElementById('code');
  if (!form || !emailInput || !codeInput || !window.Aloute) return;

  const emailFromUrl = new URLSearchParams(window.location.search).get('email')?.trim() || '';
  if (emailFromUrl) emailInput.value = emailFromUrl;
  (emailInput.value ? codeInput : emailInput).focus();

  codeInput.addEventListener('input', function () {
    this.value = this.value.replace(/\D/g, '').slice(0, 6);
  });

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    const errorBox = document.getElementById('formError');
    const successBox = document.getElementById('formSuccess');
    const submitBtn = document.getElementById('submitBtn');
    errorBox.style.display = 'none';
    successBox.style.display = 'none';

    const email = emailInput.value.trim();
    const code = codeInput.value.trim();
    const newPassword = document.getElementById('newPassword').value;
    const confirmPassword = document.getElementById('confirmPassword').value;

    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      errorBox.textContent = 'Vui lòng nhập email hợp lệ.';
      errorBox.style.display = 'block';
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      errorBox.textContent = 'Mã OTP gồm đúng 6 chữ số.';
      errorBox.style.display = 'block';
      return;
    }
    if (newPassword.length < 8 || newPassword.length > 72
      || !/[A-Z]/.test(newPassword) || !/[a-z]/.test(newPassword)
      || !/\d/.test(newPassword) || !/[^\p{L}\p{N}\s]/u.test(newPassword)) {
      errorBox.textContent = 'Mật khẩu phải có 8–72 ký tự, gồm chữ hoa, chữ thường, số và ký tự đặc biệt.';
      errorBox.style.display = 'block';
      return;
    }
    if (newPassword !== confirmPassword) {
      errorBox.textContent = 'Hai mật khẩu nhập vào không khớp.';
      errorBox.style.display = 'block';
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = 'Đang xử lý...';
    try {
      await Aloute.api('/api/auth/reset-password', {
        method: 'POST', body: { email, code, newPassword }
      });
      successBox.textContent = 'Đặt lại mật khẩu thành công! Đang chuyển tới trang đăng nhập...';
      successBox.style.display = 'block';
      form.style.display = 'none';
      setTimeout(function () { window.location.assign('/login'); }, 1800);
    } catch (err) {
      errorBox.textContent = err.message
        || 'Đặt lại mật khẩu thất bại, mã OTP có thể đã hết hạn.';
      errorBox.style.display = 'block';
      submitBtn.disabled = false;
      submitBtn.textContent = 'Đặt lại mật khẩu';
    }
  });
})();
