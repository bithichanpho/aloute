/* ============================================================
   ALOUTE — app.js
   Thu vien JS dung chung cho toan bo trang (tru auth pages tu viet
   rieng phan submit form cua minh, nhung van dung Aloute.api()).

   Xac thuc: JWT duoc luu trong cookie HttpOnly "ALOUTE_TOKEN" (do
   AuthController set), trinh duyet tu dong gui kem MOI request cung
   origin (ke ca fetch), nen o day KHONG can tu set header Authorization.
   ============================================================ */

window.Aloute = (function () {

  // ---------- Goi API JSON, tu nem loi neu backend tra success:false ----------
  async function api(path, options) {
    options = options || {};
    const opts = Object.assign({}, options);
    opts.headers = Object.assign({}, opts.headers || {});

    const isFormData = opts.body instanceof FormData;
    if (!isFormData && opts.body && typeof opts.body !== 'string') {
      opts.body = JSON.stringify(opts.body);
    }
    if (!isFormData && opts.body && !opts.headers['Content-Type']) {
      opts.headers['Content-Type'] = 'application/json';
    }

    const res = await fetch(path, opts);

    let payload = null;
    try {
      payload = await res.json();
    } catch (e) {
      // phan hoi khong co body JSON (vd. loi 500 dang HTML) - bo qua
    }

    if (!res.ok || (payload && payload.success === false)) {
      const message = (payload && payload.message) || ('Loi ' + res.status);
      throw new Error(message);
    }
    return payload ? payload.data : null;
  }

  // ---------- Toast thong bao noi goc tren ----------
  function toast(message, isError) {
    let host = document.getElementById('toastHost');
    if (!host) {
      host = document.createElement('div');
      host.id = 'toastHost';
      document.body.appendChild(host);
    }
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' toast--error' : '');
    el.textContent = message;
    host.appendChild(el);
    requestAnimationFrame(() => el.classList.add('toast--show'));
    setTimeout(() => {
      el.classList.remove('toast--show');
      setTimeout(() => el.remove(), 250);
    }, 3200);
  }

  // ---------- Dinh dang thoi gian tuong doi kieu "6 gio", "22 gio", "3 ngay" ----------
  function timeAgo(isoString) {
    if (!isoString) return '';
    const then = new Date(isoString).getTime();
    const now = Date.now();
    const diffSec = Math.max(0, Math.floor((now - then) / 1000));

    if (diffSec < 60) return 'vua xong';
    const diffMin = Math.floor(diffSec / 60);
    if (diffMin < 60) return diffMin + ' phut';
    const diffHour = Math.floor(diffMin / 60);
    if (diffHour < 24) return diffHour + ' gio';
    const diffDay = Math.floor(diffHour / 24);
    if (diffDay < 7) return diffDay + ' ngay';
    const diffWeek = Math.floor(diffDay / 7);
    if (diffWeek < 4) return diffWeek + ' tuan';
    return new Date(isoString).toLocaleDateString('vi-VN');
  }

  function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function formatCount(n) {
    n = Number(n) || 0;
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace('.0', '') + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1).replace('.0', '') + 'K';
    return String(n);
  }

  // ============================================================
  // Ket noi real-time (STOMP qua SockJS). Xac thuc da xay ra ngay
  // luc bat tay HTTP dua tren cookie ALOUTE_TOKEN (xem WebSocketConfig
  // + JwtHandshakeInterceptor phia backend) - JS o day khong can biet token.
  // ============================================================
  let stompClient = null;
  let stompConnected = false;
  const readyCallbacks = [];

  function connectRealtime() {
    if (stompClient) return;
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // tat log debug on ao trong console

    stompClient.connect({}, function () {
      stompConnected = true;

      // Thong bao (like/comment/follow) real-time - cap nhat badge chuong + toast nhe
      stompClient.subscribe('/user/queue/notifications', function (frame) {
        const notif = JSON.parse(frame.body);
        bumpBadge('notifUnreadBadge');
        toast(notif.message || 'Ban co thong bao moi');
        document.dispatchEvent(new CustomEvent('aloute:notification', { detail: notif }));
      });

      // Tin nhan real-time - cap nhat badge Tin nhan; chat.js se tu subscribe them
      // de hien thi vao dung khung chat dang mo (tranh xu ly trung o day).
      stompClient.subscribe('/user/queue/messages', function (frame) {
        const msg = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:message', { detail: msg }));
      });

      // Bao "dang nhap" tu nguoi dang nhan tin (xem ChatController#typing)
      stompClient.subscribe('/user/queue/typing', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:typing', { detail: payload }));
      });

      // Bao "da xem" tin nhan (xem ChatService#markAsRead)
      stompClient.subscribe('/user/queue/read-receipts', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:read', { detail: payload }));
      });

      // Online/offline cua ban be (xem PresenceService#broadcastPresence)
      stompClient.subscribe('/user/queue/presence', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:presence', { detail: payload }));
      });

      // Loi gui tin nhan (vd: chua la ban be, noi dung rong...) - xem ChatController#sendMessage
      stompClient.subscribe('/user/queue/errors', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:chat-error', { detail: payload }));
      });

      readyCallbacks.forEach(cb => cb(stompClient));
      readyCallbacks.length = 0;
    }, function () {
      // Mat ket noi - thu ket noi lai sau 3s (mang chap chon, server restart...)
      stompConnected = false;
      stompClient = null;
      setTimeout(connectRealtime, 3000);
    });
  }

  /** Dang ky callback chay khi STOMP da ket noi xong (dung boi chat.js). */
  function onRealtimeReady(cb) {
    if (stompConnected && stompClient) cb(stompClient);
    else readyCallbacks.push(cb);
  }

  function bumpBadge(elId) {
    const el = document.getElementById(elId);
    if (!el) return;
    const current = parseInt(el.textContent, 10) || 0;
    const next = current + 1;
    el.textContent = next > 99 ? '99+' : String(next);
    el.style.display = '';
  }

  // ============================================================
  // Tuong tac tren feed: like, mo form binh luan, dang bai, xoa bai
  // ============================================================

  /** Định dạng lại toàn bộ số đếm (like/comment) kiểu "1,2K" ngay lúc tải trang. */
  function formatInitialCounts() {
    document.querySelectorAll('[data-raw]').forEach(el => {
      el.textContent = formatCount(el.dataset.raw);
    });
  }

  function initFeedInteractions() {
    document.querySelectorAll('[data-post-id]').forEach(postEl => {
      const postId = postEl.dataset.postId;

      const likeBtn = postEl.querySelector('.action--like');
      if (likeBtn && !likeBtn.dataset.bound) {
        likeBtn.dataset.bound = '1';
        likeBtn.addEventListener('click', async () => {
          likeBtn.disabled = true;
          try {
            const liked = await api('/api/posts/' + postId + '/like', { method: 'POST' });
            likeBtn.classList.toggle('is-liked', liked);
            const countEl = likeBtn.querySelector('span');
            let count = parseInt(countEl.dataset.raw || countEl.textContent, 10) || 0;
            count = liked ? count + 1 : Math.max(0, count - 1);
            countEl.dataset.raw = String(count);
            countEl.textContent = formatCount(count);
          } catch (err) {
            toast(err.message, true);
          } finally {
            likeBtn.disabled = false;
          }
        });
      }

      const replyBtn = postEl.querySelector('.action--reply');
      if (replyBtn && !replyBtn.dataset.bound) {
        replyBtn.dataset.bound = '1';
        replyBtn.addEventListener('click', () => toggleCommentBox(postEl, postId));
      }

      const repostBtn = postEl.querySelector('.action--repost');
      if (repostBtn && !repostBtn.dataset.bound) {
        repostBtn.dataset.bound = '1';
        repostBtn.addEventListener('click', () => toast('Tính năng đăng lại đang được phát triển'));
      }

      const shareBtn = postEl.querySelector('.action--share');
      if (shareBtn && !shareBtn.dataset.bound) {
        shareBtn.dataset.bound = '1';
        shareBtn.addEventListener('click', async () => {
          const link = window.location.origin + '/feed#post-' + postId;
          try {
            await navigator.clipboard.writeText(link);
            toast('Đã sao chép liên kết bài viết');
          } catch (e) {
            toast(link);
          }
        });
      }

      const deleteBtn = postEl.querySelector('.post__delete');
      if (deleteBtn && !deleteBtn.dataset.bound) {
        deleteBtn.dataset.bound = '1';
        deleteBtn.addEventListener('click', async () => {
          if (!confirm('Xoa bai viet nay?')) return;
          try {
            await api('/api/posts/' + postId, { method: 'DELETE' });
            postEl.remove();
            toast('Da xoa bai viet');
          } catch (err) {
            toast(err.message, true);
          }
        });
      }
    });
  }

  function toggleCommentBox(postEl, postId) {
    let box = postEl.querySelector('.comment-box');
    if (box) {
      box.classList.toggle('is-open');
      return;
    }
    box = document.createElement('div');
    box.className = 'comment-box is-open';
    box.innerHTML =
      '<div class="comment-list"><span class="comment-list__loading">Dang tai binh luan...</span></div>' +
      '<form class="comment-form">' +
      '  <input type="text" maxlength="500" placeholder="Viet binh luan..." required>' +
      '  <button type="submit">Gui</button>' +
      '</form>';
    postEl.querySelector('.post__body').appendChild(box);

    loadComments(postId, box.querySelector('.comment-list'));

    box.querySelector('.comment-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = box.querySelector('input');
      const content = input.value.trim();
      if (!content) return;
      try {
        await api('/api/posts/' + postId + '/comments', { method: 'POST', body: { content } });
        input.value = '';
        loadComments(postId, box.querySelector('.comment-list'));
        const replySpan = postEl.querySelector('.action--reply span');
        if (replySpan) {
          const n = (parseInt(replySpan.dataset.raw || replySpan.textContent, 10) || 0) + 1;
          replySpan.dataset.raw = String(n);
          replySpan.textContent = formatCount(n);
        }
      } catch (err) {
        toast(err.message, true);
      }
    });
  }

  async function loadComments(postId, listEl) {
    listEl.innerHTML = '<span class="comment-list__loading">Dang tai binh luan...</span>';
    try {
      const comments = await api('/api/posts/' + postId + '/comments');
      if (!comments.length) {
        listEl.innerHTML = '<span class="comment-list__empty">Chua co binh luan nao.</span>';
        return;
      }
      listEl.innerHTML = comments.map(c => (
        '<div class="comment-item">' +
        '  <img src="' + escapeHtml(c.userAvatarUrl || 'https://i.pravatar.cc/150?img=1') + '" alt="avatar">' +
        '  <div class="comment-item__body">' +
        '    <span class="comment-item__username">' + escapeHtml(c.username) + '</span>' +
        '    <p>' + escapeHtml(c.content) + '</p>' +
        '    <span class="comment-item__time">' + timeAgo(c.createdAt) + '</span>' +
        '  </div>' +
        '</div>'
      )).join('');
    } catch (err) {
      listEl.innerHTML = '<span class="comment-list__empty">Khong tai duoc binh luan.</span>';
    }
  }

  /** O soan nhanh tren dau feed (chi text, muon them anh thi dung trang /compose day du). */
  function initQuickComposeBox() {
    const box = document.querySelector('.compose');
    if (!box) return;
    const input = box.querySelector('.compose__placeholder');
    const btn = box.querySelector('.btn-post');
    if (!input || !btn) return;

    input.addEventListener('input', () => {
      btn.disabled = input.value.trim().length === 0;
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); btn.click(); }
    });

    btn.addEventListener('click', async () => {
      const content = input.value.trim();
      if (!content) return;
      btn.disabled = true;
      try {
        await api('/api/posts', { method: 'POST', body: { content } });
        toast('Da dang bai');
        location.reload();
      } catch (err) {
        toast(err.message, true);
        btn.disabled = false;
      }
    });
  }

  /** Trang /compose day du: text + anh. */
  function initComposePage() {
    const form = document.getElementById('composeForm');
    if (!form) return;

    const textarea = document.getElementById('composeContent');
    const fileInput = document.getElementById('composeImageInput');
    const preview = document.getElementById('composePreview');
    const submitBtn = document.getElementById('composeSubmit');

    textarea.addEventListener('input', () => {
      submitBtn.disabled = textarea.value.trim().length === 0;
    });

    fileInput.addEventListener('change', () => {
      const file = fileInput.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        preview.innerHTML =
          '<div class="compose-page__preview"><img src="' + reader.result + '">' +
          '<button type="button" id="removeImageBtn" aria-label="Xoa anh">&times;</button></div>';
        document.getElementById('removeImageBtn').addEventListener('click', () => {
          fileInput.value = '';
          preview.innerHTML = '';
        });
      };
      reader.readAsDataURL(file);
    });

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const content = textarea.value.trim();
      if (!content) return;

      submitBtn.disabled = true;
      submitBtn.textContent = 'Dang dang...';
      try {
        let imageUrl = null;
        if (fileInput.files[0]) {
          const fd = new FormData();
          fd.append('file', fileInput.files[0]);
          const res = await api('/api/upload/image', { method: 'POST', body: fd });
          imageUrl = res.url;
        }
        await api('/api/posts', { method: 'POST', body: { content, imageUrl } });
        toast('Da dang bai viet');
        window.location.href = '/feed';
      } catch (err) {
        toast(err.message, true);
        submitBtn.disabled = false;
        submitBtn.textContent = 'Dang';
      }
    });
  }

  // ============================================================
  // Nut Theo doi / Dang theo doi (trang ca nhan, ket qua tim kiem)
  // ============================================================
  function initFollowButtons() {
    document.querySelectorAll('.btn-follow[data-user-id]').forEach(btn => {
      if (btn.dataset.bound) return;
      btn.dataset.bound = '1';
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const userId = btn.dataset.userId;
        btn.disabled = true;
        try {
          const following = await api('/api/users/' + userId + '/follow', { method: 'POST' });
          applyFollowState(btn, following);
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      });
    });
  }

  function applyFollowState(btn, following) {
    btn.classList.toggle('is-following', following);
    btn.textContent = following ? 'Dang theo doi' : 'Theo doi';
  }

  // ============================================================
  // Tim kiem nguoi dung (/search)
  // ============================================================
  function initSearchPage() {
    const input = document.getElementById('searchInput');
    const resultsEl = document.getElementById('searchResults');
    if (!input || !resultsEl) return;

    let debounceTimer = null;
    input.addEventListener('input', () => {
      clearTimeout(debounceTimer);
      const keyword = input.value.trim();
      if (!keyword) {
        resultsEl.innerHTML = '';
        return;
      }
      debounceTimer = setTimeout(() => runSearch(keyword, resultsEl), 350);
    });
  }

  async function runSearch(keyword, resultsEl) {
    resultsEl.innerHTML = '<div class="empty-state">Dang tim kiem...</div>';
    try {
      const users = await api('/api/search/users?q=' + encodeURIComponent(keyword));
      if (!users.length) {
        resultsEl.innerHTML = '<div class="empty-state">Khong tim thay nguoi dung nao phu hop.</div>';
        return;
      }
      resultsEl.innerHTML = users.map(renderUserRow).join('');
      initFollowButtons();
    } catch (err) {
      resultsEl.innerHTML = '<div class="empty-state">' + escapeHtml(err.message) + '</div>';
    }
  }

  function renderUserRow(u) {
    const followBtn = u.followedByCurrentUser
      ? '<button class="btn-follow is-following" data-user-id="' + u.id + '">Dang theo doi</button>'
      : '<button class="btn-follow" data-user-id="' + u.id + '">Theo doi</button>';

    return (
      '<div class="user-row">' +
      '  <a href="/profile/' + u.id + '" style="display:flex;align-items:center;gap:12px;flex:1;min-width:0;text-decoration:none;color:inherit;">' +
      '    <img src="' + escapeHtml(u.avatarUrl || 'https://i.pravatar.cc/150?img=8') + '" alt="avatar">' +
      '    <div class="user-row__info">' +
      '      <div class="user-row__name">' + escapeHtml(u.fullName) + '</div>' +
      '      <div class="user-row__username">@' + escapeHtml(u.username) + '</div>' +
      (u.bio ? '<div class="user-row__bio">' + escapeHtml(u.bio) + '</div>' : '') +
      '    </div>' +
      '  </a>' +
      followBtn +
      '</div>'
    );
  }

  // ============================================================
  // Trang hoat dong / thong bao (/activity)
  // ============================================================
  function initActivityPage() {
    const markAllBtn = document.getElementById('markAllReadBtn');
    if (!markAllBtn) return;

    markAllBtn.addEventListener('click', async () => {
      try {
        await api('/api/notifications/read-all', { method: 'POST' });
        document.querySelectorAll('.notif-item--unread').forEach(el => el.classList.remove('notif-item--unread'));
        const badge = document.getElementById('notifUnreadBadge');
        if (badge) badge.style.display = 'none';
        toast('Da danh dau tat ca la da doc');
      } catch (err) {
        toast(err.message, true);
      }
    });
  }

  // ============================================================
  // Trang chinh sua ho so (/profile/edit)
  // ============================================================
  function initProfileEditPage() {
    const form = document.getElementById('profileEditForm');
    if (!form) return;

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const fullName = document.getElementById('editFullName').value.trim();
      const bio = document.getElementById('editBio').value.trim();
      try {
        await api('/api/users/me', { method: 'PUT', body: { fullName, bio } });
        toast('Da luu thay doi');
        window.location.href = '/profile/me';
      } catch (err) {
        toast(err.message, true);
      }
    });

    const avatarInput = document.getElementById('editAvatarInput');
    if (avatarInput) {
      avatarInput.addEventListener('change', async () => {
        const file = avatarInput.files[0];
        if (!file) return;
        const fd = new FormData();
        fd.append('avatar', file);
        try {
          await api('/api/users/me/avatar', { method: 'POST', body: fd });
          toast('Da cap nhat anh dai dien');
          window.location.reload();
        } catch (err) {
          toast(err.message, true);
        }
      });
    }
  }

  // ============================================================
  // Dang xuat (nut cuoi sidebar, xuat hien o moi trang da dang nhap)
  // ============================================================
  function initLogout() {
    const btn = document.getElementById('logoutBtn');
    if (!btn) return;
    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      try {
        await api('/api/auth/logout', { method: 'POST' });
      } catch (e2) {
        // du loi van dieu huong ve trang dang nhap cho chac
      }
      window.location.href = '/login';
    });
  }

  // ============================================================
  // Khoi dong khi DOM san sang
  // ============================================================
  document.addEventListener('DOMContentLoaded', function () {
    const userId = document.body.dataset.currentUserId;
    if (userId) {
      connectRealtime();
    }
    initFeedInteractions();
    formatInitialCounts();
    initQuickComposeBox();
    initComposePage();
    initFollowButtons();
    initSearchPage();
    initActivityPage();
    initProfileEditPage();
    initLogout();
  });

  return {
    api, toast, timeAgo, escapeHtml, formatCount,
    onRealtimeReady, connectRealtime,
    initFeedInteractions, initFollowButtons
  };
})();