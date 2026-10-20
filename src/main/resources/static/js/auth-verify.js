(function () {
  const form = document.getElementById('verifyForm');
  const emailInput = document.getElementById('email');
  const codeInput = document.getElementById('code');
  const successBox = document.getElementById('verifySuccess');
  const errorBox = document.getElementById('verifyError');
  const submitBtn = document.getElementById('submitBtn');
  const resendBtn = document.getElementById('resendBtn');
  const resendBox = document.getElementById('resendBox');
  const resendMessage = document.getElementById('resendMessage');
  if (!form || !emailInput || !codeInput || !resendBtn || !window.Aloute) return;

  const emailFromUrl = new URLSearchParams(window.location.search).get('email')?.trim() || '';
  if (emailFromUrl) emailInput.value = emailFromUrl;
  (emailInput.value ? codeInput : emailInput).focus();

  codeInput.addEventListener('input', function () {
    this.value = this.value.replace(/\D/g, '').slice(0, 6);
  });

  function startCooldown() {
    let remaining = 60;
    resendBtn.disabled = true;
    resendBtn.textContent = 'Gửi lại mã OTP (' + remaining + 's)';
    const timer = setInterval(function () {
      remaining -= 1;
      if (remaining <= 0) {
        clearInterval(timer);
        resendBtn.disabled = false;
        resendBtn.textContent = 'Gửi lại mã OTP';
      } else {
        resendBtn.textContent = 'Gửi lại mã OTP (' + remaining + 's)';
      }
    }, 1000);
  }

  resendBtn.addEventListener('click', async function () {
    const identifier = emailInput.value.trim();
    resendMessage.textContent = '';
    if (!identifier) {
      resendMessage.textContent = 'Vui lòng nhập email đã đăng ký.';
      return;
    }
    this.disabled = true;
    try {
      const result = await Aloute.api('/api/auth/resend-verification', {
        method: 'POST', body: { identifier }
      });
      resendMessage.textContent = result?.message
        || 'Nếu tài khoản hợp lệ, mã OTP mới đã được gửi tới email.';
      startCooldown();
    } catch (err) {
      resendMessage.textContent = err.message || 'Không thể gửi lại mã OTP.';
      this.disabled = false;
    }
  });

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    errorBox.style.display = 'none';
    const email = emailInput.value.trim();
    const code = codeInput.value.trim();

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

    submitBtn.disabled = true;
    submitBtn.textContent = 'Đang xác thực...';
    try {
      await Aloute.api('/api/auth/verify-email', {
        method: 'POST', body: { email, code }
      });
      form.style.display = 'none';
      resendBox.style.display = 'none';
      successBox.style.display = 'block';
      setTimeout(function () { window.location.assign('/login'); }, 2000);
    } catch (err) {
      errorBox.textContent = err.message
        || 'Xác thực thất bại. Vui lòng kiểm tra lại mã hoặc yêu cầu gửi lại mã OTP.';
      errorBox.style.display = 'block';
      submitBtn.disabled = false;
      submitBtn.textContent = 'Xác thực';
    }
  });
})();
