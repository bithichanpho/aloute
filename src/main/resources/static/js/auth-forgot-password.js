(function () {
  const form = document.getElementById('forgotForm');
  if (!form || !window.Aloute) return;

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    const errorBox = document.getElementById('formError');
    const successBox = document.getElementById('formSuccess');
    const submitBtn = document.getElementById('submitBtn');
    errorBox.style.display = 'none';
    successBox.style.display = 'none';

    const email = document.getElementById('email').value.trim();
    if (!email) {
      errorBox.textContent = 'Vui lòng nhập email.';
      errorBox.style.display = 'block';
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      errorBox.textContent = 'Email không đúng định dạng.';
      errorBox.style.display = 'block';
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = 'Đang gửi...';
    try {
      await Aloute.api('/api/auth/forgot-password', { method: 'POST', body: { email } });
      successBox.textContent = 'Nếu email tồn tại trong hệ thống, mã OTP đặt lại mật khẩu đã được gửi tới hộp thư của bạn. Đang chuyển tới trang nhập mã...';
      successBox.style.display = 'block';
      form.reset();
      setTimeout(function () {
        window.location.assign('/reset-password?email=' + encodeURIComponent(email));
      }, 1500);
    } catch (err) {
      errorBox.textContent = err.message || 'Gửi yêu cầu thất bại, vui lòng thử lại.';
      errorBox.style.display = 'block';
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = 'Gửi mã OTP';
    }
  });
})();
