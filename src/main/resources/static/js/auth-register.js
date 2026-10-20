(function () {
  const form = document.getElementById('registerForm');
  const pendingVerificationBox = document.getElementById('pendingVerificationBox');
  const resendPendingBtn = document.getElementById('resendPendingBtn');
  if (!form || !pendingVerificationBox || !resendPendingBtn || !window.Aloute) return;

  resendPendingBtn.addEventListener('click', async function () {
    const identifier = document.getElementById('email').value.trim()
      || document.getElementById('username').value.trim();
    const message = document.getElementById('resendPendingMessage');
    if (!identifier) {
      message.textContent = 'Vui lòng nhập email hoặc tên đăng nhập.';
      return;
    }
    this.disabled = true;
    try {
      const result = await Aloute.api('/api/auth/resend-verification', {
        method: 'POST', body: { identifier }
      });
      message.textContent = result?.message || 'Mã OTP mới đã được gửi. Hãy kiểm tra hộp thư.';
    } catch (err) {
      message.textContent = err.message || 'Không thể gửi lại mã OTP.';
    } finally {
      this.disabled = false;
    }
  });

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    const errorBox = document.getElementById('formError');
    const submitBtn = document.getElementById('submitBtn');
    errorBox.style.display = 'none';
    pendingVerificationBox.style.display = 'none';

    const fullName = document.getElementById('fullName').value.trim();
    const username = document.getElementById('username').value.trim();
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;

    if (!fullName || !username || !email || !password) {
      errorBox.textContent = 'Vui lòng điền đầy đủ thông tin.';
      errorBox.style.display = 'block';
      return;
    }
    if (username.length < 3) {
      errorBox.textContent = 'Tên đăng nhập phải từ 3 ký tự trở lên.';
      errorBox.style.display = 'block';
      return;
    }
    if (!/^[A-Za-z0-9_](?:[A-Za-z0-9_.-]{1,48}[A-Za-z0-9_])?$/.test(username)) {
      errorBox.textContent = 'Tên đăng nhập chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.';
      errorBox.style.display = 'block';
      return;
    }
    if (password.length < 8 || password.length > 72
      || !/[A-Z]/.test(password) || !/[a-z]/.test(password)
      || !/\d/.test(password) || !/[^\p{L}\p{N}\s]/u.test(password)) {
      errorBox.textContent = 'Mật khẩu phải có 8–72 ký tự, gồm chữ hoa, chữ thường, số và ký tự đặc biệt.';
      errorBox.style.display = 'block';
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = 'Đang tạo tài khoản...';
    try {
      await Aloute.api('/api/auth/register', {
        method: 'POST', body: { fullName, username, email, password }
      });
      window.location.assign('/verify-email?email=' + encodeURIComponent(email));
    } catch (err) {
      const message = err.message || 'Đăng ký thất bại, vui lòng thử lại.';
      errorBox.textContent = message;
      errorBox.style.display = 'block';
      if (message.toLowerCase().includes('chờ xác thực')) {
        pendingVerificationBox.style.display = 'block';
      }
      submitBtn.disabled = false;
      submitBtn.textContent = 'Đăng ký';
    }
  });
})();
