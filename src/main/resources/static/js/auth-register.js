// Lucian UX/UI Prompt #244 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#244
(function () {
  'use strict';
  const form = document.getElementById('registerForm');
  const root = form?.closest('.u244');
  const pendingVerificationBox = document.getElementById('pendingVerificationBox');
  const resendPendingBtn = document.getElementById('resendPendingBtn');
  if (!root || !pendingVerificationBox || !resendPendingBtn || !window.Aloute) return;

  const scroller = root.querySelector('.u244-sc');
  const summary = root.querySelector('.u244-sm');
  const list = summary.querySelector('ul');
  const count = summary.querySelector('.u244-ct');
  const errorBox = document.getElementById('formError');
  const submitBtn = document.getElementById('submitBtn');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const usernamePattern = /^[A-Za-z0-9_](?:[A-Za-z0-9_.-]{1,48}[A-Za-z0-9_])?$/;
  const fields = ['fullName', 'username', 'email', 'password'].map(id => ({
    id,
    input: form.elements.namedItem(id),
    element: form.querySelector('[data-field="' + id + '"]'),
    message: document.getElementById(id + 'Error')
  }));
  fields.forEach(field => { field.initialReadOnly = field.input.readOnly; });
  let tried = false;
  let submitting = false;
  let scrollVersion = 0;

  function validationMessage(field) {
    const value = field.id === 'password' ? field.input.value : field.input.value.trim();
    if (!value) return {
      fullName: 'Vui lòng nhập họ và tên.', username: 'Vui lòng nhập tên đăng nhập.',
      email: 'Vui lòng nhập email.', password: 'Vui lòng nhập mật khẩu.'
    }[field.id];
    if (field.id === 'fullName' && value.length > 100) return 'Họ tên tối đa 100 ký tự.';
    if (field.id === 'username') {
      if (value.length < 3 || value.length > 50) return 'Tên đăng nhập phải từ 3 đến 50 ký tự.';
      if (!usernamePattern.test(value)) return 'Chỉ dùng chữ, số, dấu chấm, gạch dưới hoặc gạch ngang; bắt đầu và kết thúc bằng chữ, số hoặc gạch dưới.';
      if (/^deleted_/i.test(value)) return 'Tên đăng nhập không được bắt đầu bằng "deleted_".';
    }
    if (field.id === 'email') {
      // Native typeMismatch excludes international/quoted addresses accepted by @Email.
      // Check basic shape here; backend validation remains authoritative for full syntax/IDN.
      const at = value.lastIndexOf('@'), local = value.slice(0, at), domain = value.slice(at + 1);
      if (value.length > 254 || at < 1 || local.length > 64 || !domain || /[\s@]/u.test(domain)
        || domain.startsWith('.') || domain.endsWith('.') || domain.includes('..')
        || /[\r\n]/.test(local) || (!local.includes('"') && /[\s@]/u.test(local))) {
        return 'Email không đúng định dạng (ví dụ: ten@example.com).';
      }
    }
    if (field.id === 'password' && new TextEncoder().encode(value).length > 72) return 'Mật khẩu không được vượt quá 72 byte UTF-8.';
    if (field.id === 'password' && (value.length < 8 || value.length > 72
      || !/\p{Uppercase}/u.test(value) || !/\p{Lowercase}/u.test(value) || !/\p{Nd}/u.test(value)
      // Mirror Character.isLetter/isDigit/isWhitespace, including NBSP/control separators.
      || !/[^\p{L}\p{Nd}\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]/u.test(value))) {
      return 'Mật khẩu phải có 8–72 ký tự, gồm chữ hoa, chữ thường, số và ký tự đặc biệt.';
    }
    return '';
  }

  function goTo(target) {
    const version = ++scrollVersion;
    const scrollerRect = scroller.getBoundingClientRect();
    const targetRect = target.getBoundingClientRect();
    const y = Math.max(0, scroller.scrollTop + targetRect.top - scrollerRect.top - 70);
    const start = scroller.scrollTop;
    const duration = reducedMotion.matches ? 0 : 520;
    const startedAt = performance.now();
    function frame(now) {
      if (version !== scrollVersion) return;
      const k = duration ? Math.min(1, (now - startedAt) / duration) : 1;
      scroller.scrollTop = start + (y - start) * (1 - Math.pow(1 - k, 3));
      if (k < 1) requestAnimationFrame(frame);
      else target.focus({ preventScroll: true });
    }
    frame(startedAt);
  }

  function state() {
    const bad = [];
    fields.forEach(field => {
      const message = validationMessage(field);
      const wrong = !!message;
      field.element.classList.toggle('is-bad', wrong);
      field.element.classList.toggle('is-good', !wrong && field.input.value.length > 0);
      field.input.setAttribute('aria-invalid', String(wrong));
      if (wrong) field.message.textContent = message;
      field.message.closest('.u244-ms').setAttribute('aria-hidden', String(!wrong));
      const descriptions = [field.id === 'password' ? 'passwordHelp' : '', wrong ? field.message.id : ''].filter(Boolean);
      if (descriptions.length) field.input.setAttribute('aria-describedby', descriptions.join(' '));
      else field.input.removeAttribute('aria-describedby');
      if (wrong) {
        bad.push(field);
        if (!list.querySelector('[data-for="' + field.id + '"]')) {
          const item = document.createElement('li');
          item.dataset.for = field.id;
          const link = document.createElement('a');
          link.href = '#' + field.id;
          link.textContent = field.element.querySelector('label').textContent;
          link.addEventListener('click', event => { event.preventDefault(); goTo(field.input); });
          item.appendChild(link);
          list.appendChild(item);
        }
      }
    });
    for (const item of list.children) item.classList.toggle('is-fixed', !bad.some(field => field.id === item.dataset.for));
    count.textContent = bad.length + ' trường cần kiểm tra';
    root.classList.toggle('is-err', bad.length > 0);
    summary.setAttribute('aria-hidden', String(bad.length === 0));
    summary.inert = bad.length === 0;
    return bad;
  }

  fields.forEach(field => field.input.addEventListener('input', () => {
    // A correction must not be interrupted by an earlier scheduled focus/scroll.
    scrollVersion++;
    if (tried) state();
  }));

  function setBusy(active) {
    submitting = active;
    submitBtn.disabled = active;
    resendPendingBtn.disabled = active;
    fields.forEach(field => { field.input.readOnly = active || field.initialReadOnly; });
    if (active) form.setAttribute('aria-busy', 'true');
    else form.removeAttribute('aria-busy');
  }

  async function postWithDeadline(url, body) {
    const controller = new AbortController();
    let timer;
    const expired = new Promise((_, reject) => {
      timer = setTimeout(() => {
        reject(new Error('Yêu cầu quá thời gian chờ, chưa xác định được kết quả. Vui lòng kiểm tra email hoặc thử lại sau.'));
        controller.abort();
      }, 30000);
    });
    try { return await Promise.race([Aloute.api(url, {method:'POST', body, signal:controller.signal}), expired]); }
    finally { clearTimeout(timer); }
  }

  window.addEventListener('pageshow', event => {
    // A completed form can be restored from the back/forward cache after the OTP page.
    // Its request has finished; do not leave its submit button and inputs permanently locked.
    if (event.persisted && submitBtn.classList.contains('is-done')) {
      setBusy(false);
      submitBtn.classList.remove('is-done');
      submitBtn.textContent = 'Đăng ký';
    }
  });

  resendPendingBtn.addEventListener('click', async function () {
    if (submitting) return;
    const identifier = form.elements.email.value.trim() || form.elements.username.value.trim();
    const message = document.getElementById('resendPendingMessage');
    if (!identifier) { message.textContent = 'Vui lòng nhập email hoặc tên đăng nhập.'; return; }
    setBusy(true);
    try {
      await postWithDeadline('/api/auth/resend-verification', { identifier });
      // The API intentionally does not confirm that an address exists or mail was delivered.
      message.textContent = 'Nếu tài khoản tồn tại và chưa xác thực, mã OTP mới đã được gửi tới email.';
    } catch (err) {
      message.textContent = err.message || 'Không thể gửi lại mã OTP.';
    } finally { setBusy(false); }
  });

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    if (submitting) return;
    errorBox.hidden = true;
    pendingVerificationBox.hidden = true;
    tried = true;
    const bad = state();
    if (bad.length) {
      bad.forEach(field => {
        field.element.classList.remove('is-shake');
        void field.element.offsetWidth;
        field.element.classList.add('is-shake');
      });
      const version = ++scrollVersion;
      setTimeout(() => {
        if (version === scrollVersion) goTo(bad[0].input);
      }, reducedMotion.matches ? 0 : 200);
      return;
    }

    setBusy(true);
    submitBtn.textContent = 'Đang tạo tài khoản...';
    const fullName = form.elements.fullName.value.trim();
    const username = form.elements.username.value.trim();
    const email = form.elements.email.value.trim();
    const password = form.elements.password.value;
    try {
      await postWithDeadline('/api/auth/register', { fullName, username, email, password });
      submitBtn.classList.add('is-done');
      submitBtn.textContent = 'Đã gửi yêu cầu xác thực';
      window.location.assign('/verify-email?email=' + encodeURIComponent(email));
    } catch (err) {
      const message = err.message || 'Đăng ký thất bại, vui lòng thử lại.';
      errorBox.textContent = message;
      errorBox.hidden = false;
      if (message.toLowerCase().includes('chờ xác thực')) pendingVerificationBox.hidden = false;
      setBusy(false);
      submitBtn.textContent = 'Đăng ký';
      goTo(errorBox);
    }
  });
})();
