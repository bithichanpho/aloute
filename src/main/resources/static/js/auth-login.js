(function () {
  const loginForm = document.getElementById('loginForm');
  const credentialsStep = document.getElementById('credentialsStep');
  const twoFactorStep = document.getElementById('twoFactorStep');
  const twoFactorCode = document.getElementById('twoFactorCode');
  const backToLoginBtn = document.getElementById('backToLoginBtn');
  if (!loginForm || !window.Aloute) return;

  let awaitingTwoFactor = false;

  function showTwoFactorStep() {
    awaitingTwoFactor = true;
    credentialsStep.style.display = 'none';
    twoFactorStep.style.display = 'block';
    backToLoginBtn.style.display = 'block';
    document.getElementById('submitBtn').textContent = 'Xác nhận và đăng nhập';
    twoFactorCode.focus();
  }

  backToLoginBtn.addEventListener('click', function () {
    awaitingTwoFactor = false;
    twoFactorCode.value = '';
    twoFactorStep.style.display = 'none';
    credentialsStep.style.display = 'block';
    backToLoginBtn.style.display = 'none';
    document.getElementById('formError').style.display = 'none';
    document.getElementById('submitBtn').textContent = 'Đăng nhập';
    document.getElementById('username').focus();
  });

  if (new URLSearchParams(window.location.search).get('registered')) {
    const notice = document.createElement('div');
    notice.className = 'form-success';
    notice.textContent = 'Đã ghi nhận đăng ký. Hãy kiểm tra email để lấy mã OTP rồi xác thực.';
    loginForm.prepend(notice);
    document.getElementById('resendVerificationBox').style.display = 'block';
  }

  document.getElementById('resendVerificationBtn').addEventListener('click', async function () {
    const identifier = document.getElementById('username').value.trim();
    const message = document.getElementById('resendVerificationMessage');
    if (!identifier) {
      message.textContent = 'Hãy nhập email hoặc tên đăng nhập ở ô phía trên.';
      return;
    }
    this.disabled = true;
    document.getElementById('openVerificationBtn').href = identifier.includes('@')
      ? '/verify-email?email=' + encodeURIComponent(identifier) : '/verify-email';
    try {
      const result = await Aloute.api('/api/auth/resend-verification', {
        method: 'POST', body: { identifier }
      });
      message.textContent = result?.message || 'Nếu tài khoản hợp lệ, mã OTP mới đã được gửi.';
    } catch (err) {
      message.textContent = err.message || 'Không thể gửi lại mã OTP.';
    } finally {
      this.disabled = false;
    }
  });

  loginForm.addEventListener('submit', async function (event) {
    event.preventDefault();
    const errorBox = document.getElementById('formError');
    const submitBtn = document.getElementById('submitBtn');
    errorBox.style.display = 'none';

    const username = document.getElementById('username').value.trim();
    const password = document.getElementById('password').value;
    if (!username || !password) {
      errorBox.textContent = 'Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.';
      errorBox.style.display = 'block';
      return;
    }
    if (awaitingTwoFactor && !twoFactorCode.value.trim()) {
      errorBox.textContent = 'Vui lòng nhập mã xác thực hoặc mã khôi phục.';
      errorBox.style.display = 'block';
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = awaitingTwoFactor ? 'Đang xác nhận...' : 'Đang đăng nhập...';
    try {
      await Aloute.api('/api/auth/login', {
        method: 'POST', body: {
          username, password, code: twoFactorCode.value.trim()
        }
      });
      window.location.assign('/feed');
    } catch (err) {
      const message = err.message || 'Đăng nhập thất bại, vui lòng thử lại.';
      if (message.includes('xác thực hai lớp')) {
        showTwoFactorStep();
        errorBox.style.display = 'none';
        submitBtn.disabled = false;
        return;
      }
      errorBox.textContent = message;
      errorBox.style.display = 'block';
      if (message.toLowerCase().includes('xác thực email')) {
        document.getElementById('resendVerificationBox').style.display = 'block';
        document.getElementById('openVerificationBtn').href = username.includes('@')
          ? '/verify-email?email=' + encodeURIComponent(username) : '/verify-email';
      }
      submitBtn.disabled = false;
      submitBtn.textContent = awaitingTwoFactor ? 'Xác nhận và đăng nhập' : 'Đăng nhập';
    }
  });
})();
