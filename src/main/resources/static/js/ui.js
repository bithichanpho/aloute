/* ALOUTE UI helpers: đổi theme sáng/tối + hộp thoại xác nhận/nhập liệu bằng Bootstrap Modal.
 * Được nạp một lần khi tải trang. app.js dùng "soft navigation" (thay body.innerHTML) nên mọi
 * sự kiện ở đây đều gắn vào document (event delegation) và tìm phần tử mỗi lần gọi. */
(function () {
  'use strict';

  /* ---------- Theme (data-bs-theme trên <html>) ---------- */
  var THEME_KEY = 'aloute-theme';

  function currentTheme() {
    return document.documentElement.getAttribute('data-bs-theme') === 'light' ? 'light' : 'dark';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-bs-theme', theme);
    document.documentElement.style.colorScheme = theme;
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'theme-color';
      document.head.appendChild(meta);
    }
    meta.content = theme === 'light' ? '#ffffff' : '#000000';
  }

  function setTheme(theme) {
    applyTheme(theme);
    try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* bỏ qua */ }
    document.dispatchEvent(new CustomEvent('aloute:themechange', { detail: { theme: theme } }));
  }

  applyTheme(currentTheme());

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-theme-toggle]');
    if (!btn) return;
    e.preventDefault();
    setTheme(currentTheme() === 'dark' ? 'light' : 'dark');
  });

  // Synchronize the most recent choice across every open ALOUTE tab.
  window.addEventListener('storage', function (event) {
    if (event.key !== THEME_KEY) return;
    if (event.newValue !== 'light' && event.newValue !== 'dark') return;
    applyTheme(event.newValue);
    document.dispatchEvent(new CustomEvent('aloute:themechange', {
      detail: { theme: event.newValue }
    }));
  });

  /* ---------- Modal dùng chung ---------- */
  function getModal(id) {
    var el = document.getElementById(id);
    if (!el || !window.bootstrap) return null;
    return { el: el, modal: window.bootstrap.Modal.getOrCreateInstance(el) };
  }

  /**
   * confirm({ title, message, okText, cancelText, danger }) -> Promise<boolean>
   * Cũng chấp nhận confirm('chuỗi thông báo') cho dễ thay thế window.confirm.
   */
  function confirmDialog(opts) {
    if (typeof opts === 'string') opts = { message: opts };
    opts = opts || {};
    var ctx = getModal('alouteConfirmModal');
    if (!ctx) return Promise.resolve(false); // Thiếu modal: từ chối an toàn, không bật hộp thoại trình duyệt.

    var el = ctx.el;
    var okBtn = el.querySelector('#alouteConfirmOk');
    var cancelBtn = el.querySelector('#alouteConfirmCancel');
    el.querySelector('#alouteConfirmTitle').textContent = opts.title || 'Xác nhận';
    el.querySelector('#alouteConfirmMessage').textContent = opts.message || '';
    okBtn.textContent = opts.okText || 'Đồng ý';
    cancelBtn.textContent = opts.cancelText || 'Hủy';
    okBtn.classList.toggle('text-danger', opts.danger !== false);
    okBtn.classList.toggle('text-primary', opts.danger === false);

    return new Promise(function (resolve) {
      var result = false;
      function onOk() { result = true; ctx.modal.hide(); }
      function onHidden() {
        okBtn.removeEventListener('click', onOk);
        el.removeEventListener('hidden.bs.modal', onHidden);
        resolve(result);
      }
      okBtn.addEventListener('click', onOk);
      el.addEventListener('hidden.bs.modal', onHidden);
      ctx.modal.show();
    });
  }

  /**
   * prompt({ title, label, placeholder, okText, required }) -> Promise<string|null>
   * Trả về null nếu người dùng huỷ hoặc để trống khi required (mặc định true).
   */
  function promptDialog(opts) {
    if (typeof opts === 'string') opts = { label: opts };
    opts = opts || {};
    var ctx = getModal('aloutePromptModal');
    if (!ctx) return Promise.resolve(null); // Thiếu modal: huỷ an toàn, không bật hộp thoại trình duyệt.

    var el = ctx.el;
    var input = el.querySelector('#aloutePromptInput');
    var okBtn = el.querySelector('#aloutePromptOk');
    el.querySelector('#aloutePromptTitle').textContent = opts.title || 'Nhập nội dung';
    el.querySelector('#aloutePromptLabel').textContent = opts.label || '';
    el.querySelector('#aloutePromptCancel').textContent = opts.cancelText || 'Hủy';
    okBtn.textContent = opts.okText || 'Gửi';
    input.placeholder = opts.placeholder || '';
    input.value = '';

    return new Promise(function (resolve) {
      var result = null;
      function submit() {
        var value = input.value.trim();
        if (!value && opts.required !== false) { input.focus(); return; }
        result = value;
        ctx.modal.hide();
      }
      function onShown() { input.focus(); }
      function onHidden() {
        okBtn.removeEventListener('click', submit);
        el.removeEventListener('shown.bs.modal', onShown);
        el.removeEventListener('hidden.bs.modal', onHidden);
        resolve(result);
      }
      okBtn.addEventListener('click', submit);
      el.addEventListener('shown.bs.modal', onShown);
      el.addEventListener('hidden.bs.modal', onHidden);
      ctx.modal.show();
    });
  }

  window.AlouteUI = {
    confirm: confirmDialog,
    prompt: promptDialog,
    getTheme: currentTheme,
    setTheme: setTheme
  };
})();
