(function () {
  'use strict';
  const root = document.querySelector('[data-admin-accounts]');
  if (!root || !window.Aloute || typeof window.Aloute.api !== 'function') return;

  const api = window.Aloute.api;
  const escapeHtml = window.Aloute.escapeHtml || (value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch])));
  const currentUserId = Number(root.dataset.currentUserId || document.body.dataset.currentUserId || 0);
  const isHost = String(root.dataset.isHost).toLowerCase() === 'true';
  const form = document.getElementById('adminAccountSearchForm');
  const queryInput = document.getElementById('adminAccountQuery');
  const resetButton = document.getElementById('adminAccountReset');
  const rows = document.getElementById('adminAccountsRows');
  const message = document.getElementById('adminAccountsMessage');
  const pageInfo = document.getElementById('adminAccountsPageInfo');
  const previousButton = document.getElementById('adminAccountsPrev');
  const nextButton = document.getElementById('adminAccountsNext');
  const actionDialog = document.getElementById('adminAccountActionDialog');
  const actionReason = document.getElementById('adminAccountActionReason');
  const actionTitle = document.getElementById('adminAccountActionTitle');
  const actionDescription = document.getElementById('adminAccountActionDescription');
  const actionConfirm = document.getElementById('adminAccountActionConfirm');
  const detailDialog = document.getElementById('adminAccountDetailDialog');
  const detailContent = document.getElementById('adminAccountDetailContent');
  const pageSize = 20;
  let currentPage = 0;
  let currentQuery = '';
  let totalPages = 0;
  let pendingAction = null;
  let busy = false;

  const roleLabel = role => ({ROLE_HOST:'HOST · Chủ sở hữu nền tảng', ROLE_ADMIN:'ADMIN · Quản trị viên', ROLE_USER:'USER · Người dùng'}[role] || role || 'Chưa gán');
  const dateLabel = value => {
    if (!value) return '—';
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString('vi-VN');
  };
  const isDeletedAccount = account => Boolean(account && (
    account.deleted === true || String(account.username || '').toLowerCase().startsWith('deleted~') ||
    String(account.username || '').toLowerCase().startsWith('deleted_') ||
    String(account.email || '').toLowerCase().endsWith('@invalid.local') ||
    String(account.fullName || '').trim().toLowerCase() === 'tài khoản đã xóa'
  ));
  const statusLabel = account => isDeletedAccount(account)
    ? '<span class="admin-accounts__status is-deleted">Đã xóa</span>'
    : account.active ? '<span class="admin-accounts__status is-active">Đang hoạt động</span>'
    : (account.selfDeactivated ? '<span class="admin-accounts__status">Tự vô hiệu hóa</span>' : '<span class="admin-accounts__status is-locked">Đã khóa</span>');

  function allowedToManage(account) {
    if (!account || Number(account.id) === currentUserId || account.role === 'ROLE_HOST') return false;
    if (account.role === 'ROLE_ADMIN' && !isHost) return false;
    return true;
  }

  function actionButtons(account) {
    const detail = `<button type="button" data-action="detail" data-id="${escapeHtml(account.id)}">Chi tiết</button>`;
    if (isDeletedAccount(account)) return `<div class="admin-accounts__actions">${detail}</div>`;
    if (!allowedToManage(account)) return `<div class="admin-accounts__actions">${detail}<span class="admin-accounts__protected">Được bảo vệ</span></div>`;
    const lockOrUnlock = account.active
      ? `<button type="button" class="is-danger" data-action="lock" data-id="${escapeHtml(account.id)}">Khóa</button>`
      : `<button type="button" data-action="unlock" data-id="${escapeHtml(account.id)}">Mở khóa</button>`;
    return `<div class="admin-accounts__actions">${detail}${lockOrUnlock}<button type="button" data-action="force-logout" data-id="${escapeHtml(account.id)}">Buộc đăng xuất</button></div>`;
  }

  function renderAccounts(accounts) {
    if (!accounts.length) {
      rows.innerHTML = '<tr><td colspan="6" class="admin-accounts__empty">Không tìm thấy tài khoản phù hợp.</td></tr>';
      return;
    }
    rows.innerHTML = accounts.map(account => `<tr>
      <td><strong>${escapeHtml(account.fullName || account.username || '—')}</strong><small>@${escapeHtml(account.username || '')}</small><small>${escapeHtml(account.email || '')}</small></td>
      <td><span class="admin-accounts__role-chip">${escapeHtml(roleLabel(account.role))}</span></td>
      <td>${statusLabel(account)}</td>
      <td><span class="admin-accounts__verified ${account.emailVerified ? 'is-verified' : ''}">${account.emailVerified ? 'Đã xác minh' : 'Chưa xác minh'}</span></td>
      <td>${escapeHtml(dateLabel(account.createdAt))}</td>
      <td>${actionButtons(account)}</td>
    </tr>`).join('');
  }

  async function loadAccounts() {
    if (busy) return;
    busy = true;
    message.textContent = 'Đang tải danh sách tài khoản…';
    previousButton.disabled = nextButton.disabled = true;
    try {
      const params = new URLSearchParams({ q: currentQuery, page: String(currentPage), size: String(pageSize) });
      const page = await api('/api/admin/accounts?' + params.toString());
      const items = Array.isArray(page && page.items) ? page.items : [];
      totalPages = Number(page && page.totalPages || 0);
      renderAccounts(items);
      pageInfo.textContent = `Trang ${totalPages ? currentPage + 1 : 0}/${totalPages} · ${Number(page && page.totalElements || 0)} tài khoản`;
      previousButton.disabled = currentPage <= 0;
      nextButton.disabled = !page || !page.hasMore;
      message.textContent = 'Danh sách đã được cập nhật.';
    } catch (error) {
      rows.innerHTML = '<tr><td colspan="6" class="admin-accounts__empty">Không tải được danh sách. Vui lòng thử lại.</td></tr>';
      message.textContent = error && error.message ? error.message : 'Không tải được danh sách tài khoản.';
      if (window.Aloute.toast) window.Aloute.toast(message.textContent, true);
    } finally { busy = false; }
  }

  function openAction(action, accountId) {
    pendingAction = { action, accountId: Number(accountId) };
    const titles = { lock: 'Khóa tài khoản', unlock: 'Mở khóa tài khoản', 'force-logout': 'Buộc đăng xuất' };
    const descriptions = {
      lock: 'Tài khoản sẽ bị khóa và các phiên hiện tại bị thu hồi. Lý do khóa cần ít nhất 5 ký tự.',
      unlock: 'Kích hoạt lại tài khoản. Các phiên đã thu hồi không được khôi phục; người dùng cần đăng nhập lại.',
      'force-logout': 'Thu hồi toàn bộ phiên đăng nhập hiện có của tài khoản.'
    };
    actionTitle.textContent = titles[action] || 'Xác nhận thao tác';
    actionDescription.textContent = descriptions[action] || '';
    actionReason.value = '';
    actionConfirm.disabled = false;
    actionConfirm.textContent = 'Xác nhận';
    actionConfirm.classList.toggle('is-danger', action === 'lock');
    actionDialog.showModal();
  }

  async function showDetails(id) {
    detailContent.textContent = 'Đang tải…';
    detailDialog.showModal();
    try {
      const detail = await api('/api/admin/accounts/' + encodeURIComponent(id));
      const account = detail && detail.account;
      const history = Array.isArray(detail && detail.recentHistory) ? detail.recentHistory : [];
      if (!account) throw new Error('Không tìm thấy thông tin tài khoản.');
      detailContent.innerHTML = `<dl class="admin-accounts__detail-grid">
        <dt>ID</dt><dd>${escapeHtml(account.id)}</dd><dt>Username</dt><dd>${escapeHtml(account.username)}</dd>
        <dt>Họ tên</dt><dd>${escapeHtml(account.fullName || '—')}</dd><dt>Email</dt><dd>${escapeHtml(account.email || '—')}</dd>
        <dt>Vai trò</dt><dd>${escapeHtml(roleLabel(account.role))}</dd><dt>Trạng thái</dt><dd>${isDeletedAccount(account) ? 'Đã xóa' : (account.active ? 'Đang hoạt động' : (account.selfDeactivated ? 'Tự vô hiệu hóa' : 'Đã khóa'))}</dd>
        <dt>Email</dt><dd>${account.emailVerified ? 'Đã xác minh' : 'Chưa xác minh'}</dd><dt>Tạo lúc</dt><dd>${escapeHtml(dateLabel(account.createdAt))}</dd>
      </dl><h3>Lịch sử quản trị gần đây</h3>${history.length ? `<ul class="admin-accounts__history">${history.map(item => `<li><strong>${escapeHtml(item.action)}</strong><span>${escapeHtml(item.reason || 'Không có ghi chú')}</span><small>${escapeHtml(dateLabel(item.createdAt))} · Người thực hiện ID ${escapeHtml(item.actorId ?? '—')}</small></li>`).join('')}</ul>` : '<p>Chưa có lịch sử quản trị.</p>'}`;
    } catch (error) {
      detailContent.textContent = error && error.message ? error.message : 'Không thể tải chi tiết tài khoản.';
    }
  }

  rows.addEventListener('click', event => {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const action = button.dataset.action;
    if (action === 'detail') showDetails(button.dataset.id);
    else if (['lock', 'unlock', 'force-logout'].includes(action)) openAction(action, button.dataset.id);
  });

  actionConfirm.addEventListener('click', async () => {
    if (!pendingAction) return;
    const reason = actionReason.value.trim();
    if (pendingAction.action === 'lock' && reason.length < 5) {
      if (window.Aloute.toast) window.Aloute.toast('Lý do khóa phải có ít nhất 5 ký tự.', true);
      actionReason.focus();
      return;
    }
    actionConfirm.disabled = true;
    actionConfirm.textContent = 'Đang xử lý…';
    try {
      await api('/api/admin/accounts/' + pendingAction.accountId + '/' + pendingAction.action, {
        method: 'POST', body: { reason }
      });
      actionDialog.close();
      if (window.Aloute.toast) window.Aloute.toast('Thao tác tài khoản đã hoàn tất.');
      pendingAction = null;
      await loadAccounts();
    } catch (error) {
      if (window.Aloute.toast) window.Aloute.toast(error && error.message ? error.message : 'Không thể thực hiện thao tác.', true);
      actionConfirm.disabled = false;
      actionConfirm.textContent = 'Xác nhận';
    }
  });

  actionDialog.querySelector('[data-dialog-cancel]').addEventListener('click', () => actionDialog.close());
  detailDialog.querySelectorAll('[data-dialog-close]').forEach(button => button.addEventListener('click', () => detailDialog.close()));
  form.addEventListener('submit', event => { event.preventDefault(); currentQuery = queryInput.value.trim(); currentPage = 0; loadAccounts(); });
  resetButton.addEventListener('click', () => { queryInput.value = ''; currentQuery = ''; currentPage = 0; loadAccounts(); });
  previousButton.addEventListener('click', () => { if (currentPage > 0) { currentPage--; loadAccounts(); } });
  nextButton.addEventListener('click', () => { if (currentPage + 1 < totalPages) { currentPage++; loadAccounts(); } });
  loadAccounts();
})();
