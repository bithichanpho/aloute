

window.Aloute = (function () {

  function profileUrl(username, legacyId) {
    return username && String(username).trim()
      ? '/people/' + encodeURIComponent(String(username).trim())
      : '/profile/' + encodeURIComponent(legacyId == null ? 'me' : legacyId);
  }

  function csrfTokenFromCookie() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    if (!match) return '';
    try { return decodeURIComponent(match[1]); } catch (_) { return match[1]; }
  }

  function csrfHeaders(headers, method) {
    const result = Object.assign({}, headers || {});
    const verb = String(method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(verb)) {
      const token = csrfTokenFromCookie();
      if (token && !Object.keys(result).some(key => key.toLowerCase() === 'x-xsrf-token')) {
        result['X-XSRF-TOKEN'] = token;
      }
    }
    return result;
  }

  let sessionRefreshPromise = null;
  let logoutInProgress = false;

  function refreshSessionOnce() {
    // Do not issue fresh credentials while the user is ending this browser's session.
    if (logoutInProgress) return Promise.resolve(null);
    if (sessionRefreshPromise) return sessionRefreshPromise;
    sessionRefreshPromise = fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      headers: csrfHeaders({}, 'POST')
    }).catch(function () {
      return null;
    }).finally(function () {
      sessionRefreshPromise = null;
    });
    return sessionRefreshPromise;
  }

  async function fetchWithSessionRefresh(path, options) {
    const opts = Object.assign({ credentials: 'same-origin' }, options || {});
    let response = await fetch(path, opts);
    const requestPath = String(path);
    if (response.status !== 401 || requestPath.startsWith('/api/auth/')) return response;

    // Concurrent API/WS failures share one rotation request, avoiding normal parallel requests
    // being mistaken for refresh-token replay. Retry each original request once after rotation.
    const refreshResponse = await refreshSessionOnce();
    if (refreshResponse && refreshResponse.ok) response = await fetch(path, opts);
    return response;
  }

  async function api(path, options) {
    options = options || {};
    const opts = Object.assign({}, options);
    opts.method = String(opts.method || 'GET').toUpperCase();
    opts.headers = csrfHeaders(opts.headers, opts.method);
    if (!opts.credentials) opts.credentials = 'same-origin';

    const isFormData = opts.body instanceof FormData;
    if (!isFormData && opts.body && typeof opts.body !== 'string') {
      opts.body = JSON.stringify(opts.body);
    }
    if (!isFormData && opts.body && !opts.headers['Content-Type']) {
      opts.headers['Content-Type'] = 'application/json';
    }

    let res;
    try {
      res = await fetchWithSessionRefresh(path, opts);
    } catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new Error('Không thể kết nối đến máy chủ. Vui lòng kiểm tra kết nối và thử lại.');
    }

    let payload = null;
    try {
      payload = await res.json();
    } catch (e) {

    }

    if (!res.ok || (payload && payload.success === false)) {
      const message = (payload && payload.message) || ('Máy chủ trả về lỗi ' + res.status);
      const error = new Error(message);
      error.status = res.status;
      throw error;
    }
    return payload ? payload.data : null;
  }

  function queueFlashToast(message, isError) {
    try {
      sessionStorage.setItem('aloute:flash-toast', JSON.stringify({ message: String(message || ''), isError: !!isError }));
    } catch (_) {
      toast(message, isError);
    }
  }

  function consumeFlashToast() {
    try {
      const raw = sessionStorage.getItem('aloute:flash-toast');
      if (!raw) return;
      sessionStorage.removeItem('aloute:flash-toast');
      const flash = JSON.parse(raw);
      if (flash && typeof flash.message === 'string') toast(flash.message, !!flash.isError);
    } catch (_) {
      try { sessionStorage.removeItem('aloute:flash-toast'); } catch (_) {}
    }
  }

  function toast(message, isError) {
    const text = message == null ? '' : String(message).trim();
    if (!text) return;

    let host = document.getElementById('toastHost');
    if (!host) {
      host = document.createElement('div');
      host.id = 'toastHost';
      host.className = 'toast-container position-fixed top-0 start-50 translate-middle-x p-3';
      document.body.appendChild(host);
    }

    // Do not stack the same visible error when one request triggers several handlers.
    const toastKey = (isError ? 'error:' : 'info:') + text;
    const alreadyVisible = Array.from(host.querySelectorAll('.aloute-toast'))
      .some(item => item.dataset.toastKey === toastKey);
    if (alreadyVisible) return;

    const el = document.createElement('div');
    el.className = 'toast align-items-center border-0 aloute-toast' + (isError ? ' aloute-toast--error' : '');
    el.dataset.toastKey = toastKey;
    el.setAttribute('role', isError ? 'alert' : 'status');
    el.setAttribute('aria-live', isError ? 'assertive' : 'polite');
    el.setAttribute('aria-atomic', 'true');

    const row = document.createElement('div');
    row.className = 'd-flex';
    const body = document.createElement('div');
    body.className = 'toast-body';
    body.textContent = text;
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'btn-close me-2 m-auto';
    close.setAttribute('data-bs-dismiss', 'toast');
    close.setAttribute('aria-label', 'Đóng');
    row.appendChild(body);
    row.appendChild(close);
    el.appendChild(row);
    host.appendChild(el);

    el.addEventListener('hidden.bs.toast', () => el.remove());
    if (window.bootstrap && window.bootstrap.Toast) {
      window.bootstrap.Toast.getOrCreateInstance(el, { delay: 3200 }).show();
    } else {
      el.classList.add('show');
      setTimeout(() => el.remove(), 3200);
    }
  }

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

  let stompClient = null;
  let stompConnected = false;
  window.__alouteRealtimeConnected = false;
  let realtimeStarting = false;
  let realtimeLibsPromise = null;
  const readyCallbacks = [];

  const REALTIME_LIBS = [
    '/vendor/sockjs-client/sockjs.min.js',
    '/vendor/stomp/stomp.min.js'
  ];

  function loadScriptOnce(src) {
    return new Promise(function (resolve, reject) {
      const el = document.createElement('script');
      el.src = src;
      el.async = true;
      el.onload = function () { resolve(); };
      el.onerror = function () { reject(new Error('Khong tai duoc ' + src)); };
      document.head.appendChild(el);
    });
  }

  function ensureRealtimeLibs() {
    if (window.SockJS && window.Stomp) return Promise.resolve();
    if (!realtimeLibsPromise) {
      realtimeLibsPromise = REALTIME_LIBS.reduce(function (chain, src) {
        return chain.then(function () { return loadScriptOnce(src); });
      }, Promise.resolve()).catch(function (err) {
        realtimeLibsPromise = null;
        throw err;
      });
    }
    return realtimeLibsPromise;
  }

  function acknowledgeDeliveredMessage(message) {
    if (!message || !message.id || !stompClient || !stompConnected) return;
    const me = String(document.body.dataset.currentUserId || '');
    if (!me || String(message.senderId || '') === me) return;
    // ACK only persisted messages that arrived for this authenticated user/session.
    try {
      stompClient.send('/app/chat.delivered', {}, JSON.stringify({ messageId: String(message.id) }));
    } catch (error) {
      // A reconnect will deliver/ACK subsequent events; do not break message rendering.
    }
  }

  function connectRealtime() {
    if (stompClient || realtimeStarting) return;
    realtimeStarting = true;
    ensureRealtimeLibs().then(function () {
      realtimeStarting = false;
      openStompConnection();
    }).catch(function () {
      realtimeStarting = false;
      setTimeout(connectRealtime, 5000);
    });
  }

  function openStompConnection() {
    if (stompClient) return;
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function () {
      stompConnected = true;
      window.__alouteRealtimeConnected = true;

      stompClient.subscribe('/user/queue/notifications', function (frame) {
        const notif = JSON.parse(frame.body);
        toast(notif.content || notif.message || 'Bạn có thông báo mới');
        document.dispatchEvent(new CustomEvent('aloute:notification', { detail: notif }));
        window.setTimeout(function () { refreshNavBadges(true); }, 180);
      });

      // Follow-request badges are pushed over the same authenticated STOMP connection as likes/chat,
      // so recipients do not have to wait for the 30-second navigation-badge refresh interval.
      stompClient.subscribe('/user/queue/follow-requests', function (frame) {
        let payload;
        try { payload = JSON.parse(frame.body || '{}'); } catch (e) { return; }
        const currentUserId = String(document.body.dataset.currentUserId || '');
        const followerId = String(payload.followerId == null ? '' : payload.followerId);
        const targetId = String(payload.targetId == null ? '' : payload.targetId);
        if (followerId === currentUserId && targetId && payload.status) {
          document.querySelectorAll('.btn-follow[data-user-id]').forEach(function (button) {
            if (String(button.dataset.userId || '') === targetId) applyFollowStatus(button, payload.status);
          });
        }
        if (targetId === currentUserId) {
          if (payload.eventType === 'REQUESTS_SEEN') {
            followRequestBadgeRevision++;
            setBadge('followRequestBadge', 0);
          }
          document.dispatchEvent(new CustomEvent('aloute:follow-requests-changed', { detail: payload }));
          refreshNavBadges(true);
        }
      });

      stompClient.subscribe('/user/queue/messages', function (frame) {
        const msg = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:message', { detail: msg }));
        acknowledgeDeliveredMessage(msg);
        handleRealtimeChatNotice(msg);
      });

      stompClient.subscribe('/user/queue/delivery-receipts', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:delivered', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/typing', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:typing', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/read-receipts', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:read', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/presence', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:presence', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/avatar-updates', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:avatar-update', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/call', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:call-signal', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/errors', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:chat-error', { detail: payload }));
      });

      stompClient.subscribe('/user/queue/group-updates', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:group-update', { detail: payload }));
      });

      document.dispatchEvent(new CustomEvent('aloute:realtime-ready', { detail: { client: stompClient } }));
      readyCallbacks.forEach(cb => cb(stompClient));
      readyCallbacks.length = 0;
    }, function (frame) {
      stompConnected = false;
      window.__alouteRealtimeConnected = false;
      stompClient = null;
      const detail = String((frame && frame.headers && frame.headers.message) || (frame && frame.body) || '');
      const refreshNeeded = /token|phiên|hết hạn|đăng nhập|unauthor|\b401\b|\b403\b|forbidden|handshake/i.test(detail);
      const retry = function () { setTimeout(connectRealtime, 1200); };
      if (refreshNeeded) {
        refreshSessionOnce()
          .then(function (response) {
            if (response && response.ok) {
              setTimeout(connectRealtime, 600);
              return;
            }
            if (response && (response.status === 401 || response.status === 403)) {
              window.location.assign('/login?reason=session-expired');
              return;
            }
            setTimeout(connectRealtime, 5000);
          })
          .catch(function () { setTimeout(connectRealtime, 5000); });
      } else {
        setTimeout(connectRealtime, 3000);
      }
    });
  }

  function onRealtimeReady(cb) {
    if (stompConnected && stompClient) cb(stompClient);
    else readyCallbacks.push(cb);
  }

  function handleRealtimeChatNotice(message) {
    if (!message) return;
    const me = String(document.body.dataset.currentUserId || '');
    const senderId = String(message.senderId == null ? '' : message.senderId);
    // ChatService publishes the same persisted message to sender and receiver.
    // Only an incoming message should affect unread counters or show a new-message toast.
    if (!senderId || senderId === me) return;

    window.setTimeout(function () { refreshNavBadges(true); }, 550);

    const matchingMiniWindow = Array.from(document.querySelectorAll('.friends-chat-window')).find(function (win) {
      if (!win.isConnected || win.classList.contains('is-minimized')) return false;
      if (message.groupId != null) return String(win.dataset.groupId || '') === String(message.groupId);
      return String(win.dataset.friendId || '') === senderId;
    });
    const incomingType = message.groupId != null ? 'group' : 'friend';
    const incomingId = incomingType === 'group' ? String(message.groupId) : senderId;
    const activeType = String(document.body.dataset.activeChatType || '');
    const activeId = String(document.body.dataset.activeChatId || '');
    const fullChatList = document.getElementById('chatList');
    const fullChatIsOpen = document.body.classList.contains('page-chat')
      && !document.hidden
      && Boolean(fullChatList && fullChatList.classList.contains('has-active-thread'))
      && activeType === incomingType
      && activeId === incomingId;
    if (matchingMiniWindow || fullChatIsOpen || document.hidden) return;

    const senderName = String(message.senderFullName || message.senderUsername || 'Tin nhắn mới');
    const excerpt = String(message.content || (message.mediaUrl ? 'Đã gửi tệp đính kèm' : 'Bạn có tin nhắn mới')).trim();
    const label = excerpt.length > 90 ? excerpt.slice(0, 87) + '…' : excerpt;
    toast(senderName + ': ' + label);
  }

  function formatInitialCounts() {
    document.querySelectorAll('[data-raw]').forEach(el => {
      el.textContent = formatCount(el.dataset.raw);
    });
  }

  const REACTION_EMOJI = { LIKE: '👍', LOVE: '❤️', HAHA: '😆', WOW: '😮', SAD: '😢', ANGRY: '😠' };

  function applyReactionUI(likeBtn, type, animate) {
    likeBtn.classList.toggle('is-reacted', !!type);
    Array.from(likeBtn.classList).forEach(c => { if (c.indexOf('reacted--') === 0) likeBtn.classList.remove(c); });
    if (type) likeBtn.classList.add('reacted--' + type);
    const emojiSpan = likeBtn.querySelector('.action--like__emoji');
    if (emojiSpan) {
      emojiSpan.textContent = type ? (REACTION_EMOJI[type] || '👍') : '';
      if (type && animate) {

        emojiSpan.classList.remove('reaction-bump');
        void emojiSpan.offsetWidth;
        emojiSpan.classList.add('reaction-bump');
      }
    }
  }

  function closeAllReactionPickers(except) {
    document.querySelectorAll('.reaction-picker--open').forEach(picker => {
      if (picker !== except) picker.classList.remove('reaction-picker--open');
    });
  }

  document.addEventListener('click', (e) => {
    document.querySelectorAll('.reaction-picker--open').forEach(picker => {
      if (!picker.parentElement || !picker.parentElement.contains(e.target)) {
        picker.classList.remove('reaction-picker--open');
      }
    });
  });

  async function openPostShareDialog(postEl, postId) {
    const currentUserId = document.body.dataset.currentUserId;
    if (!currentUserId) {
      toast('Bạn cần đăng nhập để gửi bài viết cho bạn bè', true);
      return;
    }

    const dialog = document.createElement('dialog');
    dialog.className = 'share-post-dialog';
    dialog.setAttribute('aria-labelledby', 'sharePostDialogTitle');

    const header = document.createElement('header');
    header.className = 'share-post-dialog__header';
    const headingWrap = document.createElement('div');
    const eyebrow = document.createElement('span');
    eyebrow.className = 'share-post-dialog__eyebrow';
    eyebrow.textContent = 'ALOUTE MESSENGER';
    const title = document.createElement('h2');
    title.id = 'sharePostDialogTitle';
    title.textContent = 'Gửi bài viết cho bạn bè';
    headingWrap.append(eyebrow, title);
    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'share-post-dialog__close';
    closeBtn.setAttribute('aria-label', 'Đóng cửa sổ chia sẻ');
    closeBtn.innerHTML = '&times;';
    closeBtn.addEventListener('click', () => dialog.close());
    header.append(headingWrap, closeBtn);

    const preview = document.createElement('div');
    preview.className = 'share-post-preview';
    const previewIcon = document.createElement('span');
    previewIcon.className = 'share-post-preview__icon';
    previewIcon.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3"></rect><path d="M7 14l3-3 3 3 2-2 3 3"></path></svg>';
    const previewText = document.createElement('div');
    previewText.className = 'share-post-preview__text';
    const previewTitle = document.createElement('strong');
    previewTitle.textContent = 'Bài viết của ' + (postEl.querySelector('.post__username')?.textContent.trim() || 'ALOUTE');
    const postContent = (postEl.querySelector('.post__content')?.textContent || '').trim();
    const previewDescription = document.createElement('span');
    previewDescription.textContent = postContent || (postEl.querySelector('.post__image, .post__video') ? 'Bài viết có nội dung đa phương tiện' : 'Mở bài viết để xem nội dung');
    previewText.append(previewTitle, previewDescription);
    preview.append(previewIcon, previewText);

    const searchLabel = document.createElement('label');
    searchLabel.className = 'share-post-dialog__search';
    searchLabel.setAttribute('for', 'sharePostFriendSearch');
    const searchIcon = document.createElement('span');
    searchIcon.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.8"></circle><path d="m16 16 4.5 4.5"></path></svg>';
    const search = document.createElement('input');
    search.id = 'sharePostFriendSearch';
    search.type = 'search';
    search.placeholder = 'Tìm bạn bè...';
    search.autocomplete = 'off';
    searchLabel.append(searchIcon, search);

    const selectionSummary = document.createElement('div');
    selectionSummary.className = 'share-post-dialog__summary';
    selectionSummary.textContent = 'Chọn người nhận';
    const friendList = document.createElement('div');
    friendList.className = 'share-post-friend-list';
    friendList.setAttribute('aria-label', 'Danh sách bạn bè');
    const listStatus = document.createElement('p');
    listStatus.className = 'share-post-dialog__status';
    listStatus.textContent = 'Đang tải danh sách bạn bè...';
    friendList.append(listStatus);

    const noteLabel = document.createElement('label');
    noteLabel.className = 'share-post-dialog__note-label';
    noteLabel.textContent = 'Thêm lời nhắn (không bắt buộc)';
    const note = document.createElement('textarea');
    note.className = 'share-post-dialog__note';
    note.maxLength = 500;
    note.rows = 2;
    note.placeholder = 'Viết lời nhắn cho bạn bè...';
    noteLabel.append(note);

    const footer = document.createElement('footer');
    footer.className = 'share-post-dialog__footer';
    const copyBtn = document.createElement('button');
    copyBtn.type = 'button';
    copyBtn.className = 'share-post-dialog__secondary';
    copyBtn.textContent = 'Sao chép liên kết';
    const sendBtn = document.createElement('button');
    sendBtn.type = 'button';
    sendBtn.className = 'share-post-dialog__primary';
    sendBtn.textContent = 'Gửi bài viết';
    sendBtn.disabled = true;
    footer.append(copyBtn, sendBtn);

    dialog.append(header, preview, searchLabel, selectionSummary, friendList, noteLabel, footer);
    document.body.append(dialog);
    dialog.addEventListener('close', () => dialog.remove(), { once: true });
    dialog.addEventListener('click', event => {
      if (event.target === dialog) dialog.close();
    });
    dialog.showModal();

    const selected = new Set();
    const rows = [];
    function updateSelection() {
      selectionSummary.textContent = selected.size
        ? 'Đã chọn ' + selected.size + ' người nhận'
        : 'Chọn một hoặc nhiều người bạn';
      sendBtn.textContent = selected.size ? 'Gửi cho ' + selected.size + ' người' : 'Gửi bài viết';
      sendBtn.disabled = selected.size === 0;
    }
    function addFriendRow(friend) {
      const friendId = String(friend.id);
      const row = document.createElement('label');
      row.className = 'share-post-friend';
      row.dataset.search = ((friend.fullName || '') + ' ' + (friend.username || '')).toLocaleLowerCase('vi');
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.value = friendId;
      check.setAttribute('aria-label', 'Chọn ' + (friend.fullName || friend.username || 'bạn bè'));
      const avatar = document.createElement('img');
      avatar.className = 'share-post-friend__avatar';
      avatar.src = friend.avatarUrl || '/images/avatar_default.png';
      avatar.alt = '';
      avatar.loading = 'lazy';
      avatar.onerror = () => { avatar.onerror = null; avatar.src = '/images/avatar_default.png'; };
      const meta = document.createElement('span');
      meta.className = 'share-post-friend__meta';
      const name = document.createElement('strong');
      name.textContent = friend.fullName || friend.username || 'Người dùng ALOUTE';
      const username = document.createElement('small');
      username.textContent = friend.username ? '@' + friend.username : '';
      meta.append(name, username);
      row.append(check, avatar, meta);
      check.addEventListener('change', () => {
        if (check.checked) selected.add(friendId);
        else selected.delete(friendId);
        row.classList.toggle('is-selected', check.checked);
        updateSelection();
      });
      friendList.append(row);
      rows.push(row);
    }

    search.addEventListener('input', () => {
      const query = search.value.trim().toLocaleLowerCase('vi');
      rows.forEach(row => { row.style.display = query && !row.dataset.search.includes(query) ? 'none' : ''; });
    });

    const shareUrl = window.location.origin + '/feed?postId=' + encodeURIComponent(postId) + '#post-' + encodeURIComponent(postId);
    copyBtn.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(shareUrl);
        toast('Đã sao chép liên kết bài viết');
      } catch (error) {
        toast('Không thể sao chép liên kết. Hãy kiểm tra quyền clipboard của trình duyệt.', true);
      }
    });

    sendBtn.addEventListener('click', async () => {
      const recipientIds = Array.from(selected).map(id => Number(id)).filter(Number.isSafeInteger);
      if (!recipientIds.length) return;
      sendBtn.disabled = true;
      copyBtn.disabled = true;
      sendBtn.textContent = 'Đang gửi...';
      try {
        const result = await api('/api/posts/' + encodeURIComponent(postId) + '/share', {
          method: 'POST',
          body: { recipientIds, message: note.value.trim() }
        });
        dialog.close();
        toast('Đã gửi bài viết cho ' + (result?.sentCount || recipientIds.length) + ' người bạn');
      } catch (error) {
        toast(error.message || 'Không gửi được bài viết', true);
        sendBtn.disabled = false;
        copyBtn.disabled = false;
        sendBtn.textContent = 'Gửi cho ' + recipientIds.length + ' người';
      }
    });

    try {
      const friends = await api('/api/users/' + encodeURIComponent(currentUserId) + '/friends');
      friendList.replaceChildren();
      if (!Array.isArray(friends) || friends.length === 0) {
        listStatus.textContent = 'Bạn chưa có bạn bè theo dõi lẫn nhau. Hãy kết nối thêm bạn bè để gửi bài viết.';
        friendList.append(listStatus);
        search.disabled = true;
      } else {
        friends.forEach(addFriendRow);
        updateSelection();
      }
    } catch (error) {
      listStatus.textContent = error.message || 'Không tải được danh sách bạn bè. Vui lòng thử lại.';
      friendList.replaceChildren(listStatus);
      search.disabled = true;
    }
  }

  function linkifyPostTags(root) {
    const scope = root || document;
    scope.querySelectorAll('.post__content:not([data-tags-linked])').forEach(node => {
      node.dataset.tagsLinked = '1';
      const text = node.textContent || '';
      const pattern = /(^|[^\p{L}\p{N}_])([#@])([\p{L}\p{N}_]{1,50})/gu;
      let offset = 0;
      const fragment = document.createDocumentFragment();
      for (const match of text.matchAll(pattern)) {
        const index = match.index;
        fragment.append(document.createTextNode(text.slice(offset, index)));
        fragment.append(document.createTextNode(match[1]));
        const anchor = document.createElement('a');
        anchor.className = match[2] === '#' ? 'content-tag content-tag--hashtag' : 'content-tag content-tag--mention';
        anchor.textContent = match[2] + match[3];
        anchor.href = match[2] === '#'
          ? '/hashtag/' + encodeURIComponent(match[3].toLocaleLowerCase())
          : '/people/' + encodeURIComponent(match[3]);
        fragment.append(anchor);
        offset = index + match[0].length;
      }
      if (offset === 0) return;
      fragment.append(document.createTextNode(text.slice(offset)));
      node.replaceChildren(fragment);
    });
  }

  function bindReposterPopover(wrap) {
    if (!wrap || wrap.dataset.repostersBound === '1') return;
    const trigger = wrap.querySelector('[data-reposters-trigger]');
    const list = wrap.querySelector('[data-reposters-list]');
    const postId = wrap.dataset.repostersPostId;
    if (!trigger || !list || !postId) return;
    wrap.dataset.repostersBound = '1';
    let nextCursor = '';
    let loading = false;
    let loaded = false;

    function appendActor(actor) {
      const link = document.createElement('a');
      link.className = 'post__repost-others-list__item';
      link.href = profileUrl(actor.username, actor.userId);
      const img = document.createElement('img');
      img.src = actor.userAvatarUrl || '/images/avatar_default.png';
      img.alt = '';
      img.loading = 'lazy';
      const name = document.createElement('span');
      name.textContent = actor.username || 'người dùng';
      link.append(img, name);
      list.appendChild(link);
    }

    async function loadPage() {
      if (loading) return;
      loading = true;
      if (!loaded) {
        list.replaceChildren();
        const status = document.createElement('span');
        status.className = 'post__repost-others-status';
        status.textContent = 'Đang tải...';
        list.appendChild(status);
      }
      try {
        const cursorParam = nextCursor ? '&cursor=' + encodeURIComponent(nextCursor) : '';
        const page = await api(
          '/api/posts/' + encodeURIComponent(postId) + '/reposters?size=20' + cursorParam
        );
        if (!loaded) list.replaceChildren();
        (page.items || []).forEach(appendActor);
        loaded = true;
        nextCursor = page.nextCursor || '';
        list.querySelector('[data-reposters-more]')?.remove();
        if (page.hasMore && nextCursor) {
          const more = document.createElement('button');
          more.type = 'button';
          more.className = 'post__repost-others-more';
          more.dataset.repostersMore = '1';
          more.textContent = 'Xem thêm';
          more.addEventListener('click', function(event) {
            event.stopPropagation();
            loadPage();
          });
          list.appendChild(more);
        } else if (!list.children.length) {
          const status = document.createElement('span');
          status.className = 'post__repost-others-status';
          status.textContent = 'Chưa có người đăng lại';
          list.appendChild(status);
        }
      } catch (error) {
        if (!loaded) list.replaceChildren();
        const status = document.createElement('button');
        status.type = 'button';
        status.className = 'post__repost-others-status post__repost-others-status--error';
        status.textContent = 'Không tải được. Nhấn để thử lại';
        status.addEventListener('click', loadPage, { once: true });
        list.appendChild(status);
      } finally {
        loading = false;
      }
    }

    // Rê chuột (hoặc focus bằng bàn phím) là tải danh sách; CSS lo việc hiện/ẩn.
    wrap.addEventListener('mouseenter', function() { if (!loaded) loadPage(); });
    wrap.addEventListener('focusin', function() { if (!loaded) loadPage(); });

    // Cảm ứng: chạm để mở/đóng. Chuột: hover đã đủ nên click không đóng danh sách đang hover.
    trigger.addEventListener('click', function(event) {
      event.stopPropagation();
      wrap.classList.toggle('is-open');
      if (wrap.classList.contains('is-open') && !loaded) loadPage();
    });
    document.addEventListener('click', function(event) {
      if (!wrap.contains(event.target)) wrap.classList.remove('is-open');
    });
  }

  function initFeedInteractions() {
    linkifyPostTags(document);
    document.querySelectorAll('[data-post-id]').forEach(postEl => {

      const postId = postEl.dataset.actionPostId || postEl.dataset.postId;
      postEl.querySelectorAll('[data-reposters-post-id]').forEach(bindReposterPopover);

      const reaction = postEl.querySelector('.reaction');
      if (reaction && !reaction.dataset.bound) {
        reaction.dataset.bound = '1';

        const likeBtn = reaction.querySelector('.action--like');
        const picker = reaction.querySelector('.reaction-picker');
        const countEl = likeBtn.querySelector('span:not(.action--like__emoji)');
        const initialMatch = /reacted--([A-Z]+)/.exec(likeBtn.className);
        const initialType = initialMatch ? initialMatch[1] : null;
        applyReactionUI(likeBtn, initialType);

        const state = {
          desired: initialType,
          serverType: initialType,
          serverCount: parseInt(countEl.dataset.raw || countEl.textContent, 10) || 0,
          inFlight: false
        };

        function render(animate) {

          let delta = 0;
          if (state.desired && !state.serverType) delta = 1;
          else if (!state.desired && state.serverType) delta = -1;
          const displayedCount = Math.max(0, state.serverCount + delta);
          applyReactionUI(likeBtn, state.desired, animate);
          countEl.dataset.raw = String(displayedCount);
          countEl.textContent = formatCount(displayedCount);
        }

        async function flushReactionQueue() {
          if (state.inFlight) return;
          state.inFlight = true;
          try {
            while (state.desired !== state.serverType) {
              const requested = state.desired;
              try {
                const result = await api('/api/posts/' + postId + '/react', {
                  method: 'POST',
                  body: { type: requested }
                });
                state.serverType = result.currentUserReaction || null;
                state.serverCount = Number(result.totalCount) || 0;
                render();
              } catch (err) {
                if (state.desired === requested) {
                  state.desired = state.serverType;
                  render();
                  toast(err.message || 'Không thể cập nhật cảm xúc', true);
                  break;
                }
              }
            }
          } finally {
            state.inFlight = false;
            render();
          }
        }

        const canHover = !!(window.matchMedia && window.matchMedia('(hover: hover) and (pointer: fine)').matches);
        const OPEN_DELAY = 250;
        const CLOSE_DELAY = 300;
        let openTimer = null;
        let closeTimer = null;
        let pressTimer = null;
        let longPressed = false;

        function markSelected() {
          if (!picker) return;
          picker.querySelectorAll('.reaction-picker__emoji').forEach(btn => {
            btn.classList.toggle('is-selected', btn.dataset.reaction === state.desired);
          });
        }
        function openPicker() {
          if (!picker) return;
          clearTimeout(openTimer);
          clearTimeout(closeTimer);
          closeAllReactionPickers(picker);
          markSelected();
          picker.classList.add('reaction-picker--open');
        }
        function closePicker() {
          clearTimeout(openTimer);
          clearTimeout(closeTimer);
          if (picker) picker.classList.remove('reaction-picker--open');
        }
        function scheduleOpen() {
          clearTimeout(closeTimer);
          if (!picker || picker.classList.contains('reaction-picker--open')) return;
          clearTimeout(openTimer);
          openTimer = setTimeout(openPicker, OPEN_DELAY);
        }
        function scheduleClose() {
          clearTimeout(openTimer);
          clearTimeout(closeTimer);
          closeTimer = setTimeout(closePicker, CLOSE_DELAY);
        }

        likeBtn.addEventListener('click', (e) => {
          if (longPressed) { longPressed = false; e.preventDefault(); return; }
          closePicker();
          state.desired = state.desired ? null : 'LIKE';
          render(true);
          flushReactionQueue();
        });

        if (picker) {
          picker.querySelectorAll('.reaction-picker__emoji').forEach((emojiBtn, index) => {

            if (!emojiBtn.dataset.label) emojiBtn.dataset.label = emojiBtn.getAttribute('title') || '';
            emojiBtn.removeAttribute('title');
            emojiBtn.style.setProperty('--i', String(index));

            emojiBtn.addEventListener('click', (e) => {
              e.stopPropagation();
              const type = emojiBtn.dataset.reaction;

              state.desired = state.desired === type ? null : type;
              closePicker();
              render(true);
              flushReactionQueue();
            });
          });

          if (canHover) {

            reaction.addEventListener('mouseenter', scheduleOpen);
            reaction.addEventListener('mouseleave', scheduleClose);
          }

          likeBtn.addEventListener('touchstart', () => {
            longPressed = false;
            pressTimer = setTimeout(() => { longPressed = true; openPicker(); }, 450);
          }, { passive: true });
          const cancelPress = () => { if (pressTimer) clearTimeout(pressTimer); };
          likeBtn.addEventListener('touchend', cancelPress);
          likeBtn.addEventListener('touchmove', cancelPress);
          likeBtn.addEventListener('touchcancel', cancelPress);
          likeBtn.addEventListener('contextmenu', (e) => { if (longPressed) e.preventDefault(); });
        }
      }

      const replyBtn = postEl.querySelector('.action--reply');
      if (replyBtn && !replyBtn.dataset.bound) {
        replyBtn.dataset.bound = '1';
        replyBtn.addEventListener('click', () => toggleCommentBox(postEl, postId));
      }

      const repostBtn = postEl.querySelector('.action--repost');
      if (repostBtn && !repostBtn.dataset.bound) {
        repostBtn.dataset.bound = '1';

        const originalPostId = repostBtn.dataset.postId || postId;
        const countEl = repostBtn.querySelector('span');

        const rState = {
          desired: repostBtn.classList.contains('is-reposted'),
          serverState: repostBtn.classList.contains('is-reposted'),
          serverCount: parseInt(countEl ? (countEl.dataset.raw || countEl.textContent) : '0', 10) || 0,
          inFlight: false
        };

        function renderRepost(animate) {
          let delta = 0;
          if (rState.desired && !rState.serverState) delta = 1;
          else if (!rState.desired && rState.serverState) delta = -1;
          const displayedCount = Math.max(0, rState.serverCount + delta);

          repostBtn.classList.toggle('is-reposted', rState.desired);
          repostBtn.setAttribute('aria-pressed', rState.desired ? 'true' : 'false');
          if (countEl) {
            countEl.dataset.raw = String(displayedCount);
            countEl.textContent = formatCount(displayedCount);
          }
          if (animate && rState.desired) {
            repostBtn.classList.remove('repost-pop');
            void repostBtn.offsetWidth;
            repostBtn.classList.add('repost-pop');
          }
        }

        function updateRepostBanner(actors, otherCountFromServer) {
          let banner = postEl.querySelector('[data-repost-banner]');
          if (!Array.isArray(actors) || actors.length === 0) {
            if (banner) banner.remove();
            return;
          }
          if (!banner) {
            banner = document.createElement('div');
            banner.className = 'post__repost-banner';
            banner.dataset.repostBanner = '1';
            const avatarCol = postEl.querySelector('.post__avatar-col');
            if (avatarCol) postEl.insertBefore(banner, avatarCol);
            else postEl.prepend(banner);
          }
          banner.replaceChildren();
          const icon = document.createElement('span');
          icon.className = 'post__repost-icon';
          icon.textContent = '↻';
          banner.appendChild(icon);
          const names = document.createElement('span');
          names.className = 'post__repost-names';
          actors.slice(0, 3).forEach(function(actor, index) {
            if (index > 0) names.appendChild(document.createTextNode(index === 2 ? ' và ' : ', '));
            const link = document.createElement('a');
            link.className = 'post__reposter';
            link.href = profileUrl(actor.username, actor.userId);
            link.textContent = actor.username || 'người dùng';
            names.appendChild(link);
          });
          banner.appendChild(names);
          const otherCount = Math.max(0, Number(otherCountFromServer) || 0);
          if (otherCount > 0) {
            banner.appendChild(document.createTextNode(' cùng '));

            const wrap = document.createElement('span');
            wrap.className = 'post__repost-others';
            wrap.dataset.repostersPostId = originalPostId;
            wrap.dataset.repostersTotal = String(otherCount);
            const trigger = document.createElement('button');
            trigger.type = 'button';
            trigger.className = 'post__repost-others__trigger';
            trigger.dataset.repostersTrigger = '1';
            trigger.textContent = otherCount + ' người khác';
            wrap.appendChild(trigger);
            const list = document.createElement('div');
            list.className = 'post__repost-others-list';
            list.dataset.repostersList = '1';
            const status = document.createElement('span');
            status.className = 'post__repost-others-status';
            status.textContent = 'Đang tải...';
            list.appendChild(status);
            wrap.appendChild(list);
            banner.appendChild(wrap);
            bindReposterPopover(wrap);
          }
          const label = document.createElement('span');
          label.className = 'post__repost-label';
          label.textContent = 'đã đăng lại';
          banner.appendChild(label);
        }

        async function flushRepostQueue() {
          if (rState.inFlight) return;
          rState.inFlight = true;
          try {

            while (rState.desired !== rState.serverState) {
              const requested = rState.desired;
              try {
                const result = await api('/api/posts/' + originalPostId + '/repost', {
                  method: 'POST',
                  body: { reposted: requested }
                });
                rState.serverState = !!result.reposted;
                rState.serverCount = Number(result.repostCount) || 0;
                updateRepostBanner(result.reposters || [], result.otherReposterCount);
                renderRepost(false);
              } catch (err) {
                if (rState.desired === requested) {
                  rState.desired = rState.serverState;
                  renderRepost(false);
                  toast(err.message || 'Không thể đăng lại bài viết ngay lúc này', true);
                  break;
                }
              }
            }
          } finally {
            rState.inFlight = false;
            renderRepost(false);
          }
        }

        renderRepost(false);
        repostBtn.addEventListener('click', () => {
          rState.desired = !rState.desired;
          renderRepost(true);
          flushRepostQueue();
        });
      }

      const shareBtn = postEl.querySelector('.action--share');
      if (shareBtn && !shareBtn.dataset.bound) {
        shareBtn.dataset.bound = '1';
        shareBtn.addEventListener('click', () => openPostShareDialog(postEl, postId));
      }

      const deleteBtn = postEl.querySelector('.post__delete');
      if (deleteBtn && !deleteBtn.dataset.bound) {
        deleteBtn.dataset.bound = '1';
        deleteBtn.addEventListener('click', async () => {
          if (!(await AlouteUI.confirm({ title: 'Xóa bài viết', message: 'Bạn có chắc muốn xóa bài viết này?', okText: 'Xóa' }))) return;
          try {
            const entityPostId = postEl.dataset.postId;
            await api('/api/posts/' + entityPostId, { method: 'DELETE' });
            postEl.remove();
            toast('Da xoa bai viet');
          } catch (err) {
            toast(err.message, true);
          }
        });
      }
    });
  }

  function createCommentRequestId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    return 'comment-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  function toggleCommentBox(postEl, postId) {
    let box = postEl.querySelector('.comment-box');
    if (box) {
      box.classList.toggle('is-open');
      return;
    }
    box = document.createElement('div');
    box.className = 'comment-box is-open';
    const currentUserAvatar = escapeHtml(document.body.dataset.currentUserAvatar || '/images/avatar_default.png');
    box.innerHTML =
      '<div class="comment-list"><span class="comment-list__loading">Đang tải bình luận...</span></div>' +
      '<form class="comment-form comment-form--composer">' +
      '  <img class="comment-form__avatar" src="' + currentUserAvatar + '" alt="Ảnh đại diện của bạn" loading="lazy">' +
      '  <input type="text" maxlength="500" placeholder="Viết bình luận..." aria-label="Viết bình luận" required>' +
      '  <button type="submit">Gửi</button>' +
      '</form>';
    postEl.querySelector('.post__body').appendChild(box);

    loadComments(postId, box.querySelector('.comment-list'));
    let pendingCommentRequestId = createCommentRequestId();
    box.querySelector('input').addEventListener('input', () => {
      pendingCommentRequestId = createCommentRequestId();
    });

    box.querySelector('.comment-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = box.querySelector('input');
      const submit = box.querySelector('button[type="submit"]');
      const content = input.value.trim();
      if (!content || submit.disabled) return;
      submit.disabled = true;
      try {
        const created = await api('/api/posts/' + encodeURIComponent(postId) + '/comments', {
          method: 'POST', body: { content, clientRequestId: pendingCommentRequestId }
        });
        pendingCommentRequestId = createCommentRequestId();
        input.value = '';
        await loadComments(postId, box.querySelector('.comment-list'));
        if (created && String(created.moderationStatus || '').toUpperCase() === 'PENDING') {
          toast('Bình luận đã gửi và đang chờ kiểm duyệt.', false);
        } else {
          const replySpan = postEl.querySelector('.action--reply span');
          if (replySpan) {
            const n = (parseInt(replySpan.dataset.raw || replySpan.textContent, 10) || 0) + 1;
            replySpan.dataset.raw = String(n);
            replySpan.textContent = formatCount(n);
          }
        }
      } catch (err) {
        toast(err.message, true);
      } finally {
        submit.disabled = false;
      }
    });
  }

  function commentMarkup(comment, postId) {
    const commentId = escapeHtml(comment.id || '');
    const replies = Math.max(0, Number(comment.replyCount) || 0);
    const safePostId = escapeHtml(postId || comment.postId || '');
    const moderationStatus = String(comment.moderationStatus || 'APPROVED').toUpperCase();
    const moderationLabel = moderationStatus === 'PENDING'
      ? '<span class="comment-item__moderation-status" data-moderation-status="PENDING">Đang kiểm duyệt</span>'
      : (moderationStatus === 'FLAGGED'
        ? '<span class="comment-item__moderation-status" data-moderation-status="FLAGGED">Được Moderation đánh dấu</span>'
        : '');
    return '<div class="comment-item" data-comment-id="' + commentId + '" data-post-id="' + safePostId + '" data-reply-count="' + replies + '" data-moderation-status="' + escapeHtml(moderationStatus) + '">' +
      '  <img src="' + escapeHtml(comment.userAvatarUrl || '/images/avatar_default.png') + '" alt="avatar" loading="lazy">' +
      '  <div class="comment-item__body">' +
      '    <span class="comment-item__username">' + escapeHtml(comment.username || '') + '</span>' + moderationLabel +
      '    <p>' + escapeHtml(comment.content || '') + '</p>' +
      '    <span class="comment-item__time">' + timeAgo(comment.createdAt) + '</span>' +
      '    <div class="comment-item__actions">' +
      '      <button type="button" class="comment-link" data-comment-reply>Trả lời</button>' +
      (replies > 0 ? '      <button type="button" class="comment-link" data-show-replies>Xem ' + replies + ' phản hồi</button>' : '') +
      '    </div>' +
      '    <div class="comment-replies" data-replies-container hidden>' +
      '      <div class="comment-replies__list" data-replies-list></div>' +
      '      <button type="button" class="comment-link comment-replies__more" data-more-replies hidden>Tải thêm phản hồi</button>' +
      '    </div>' +
      '  </div>' +
      '</div>';
  }

  function wireCommentItem(postId, item) {
    if (!item || item.dataset.actionsBound === '1') return;
    item.dataset.actionsBound = '1';
    const commentId = item.dataset.commentId;
    const body = item.querySelector('.comment-item__body');
    const actions = item.querySelector('.comment-item__actions');
    const repliesContainer = item.querySelector('[data-replies-container]');
    const repliesList = item.querySelector('[data-replies-list]');
    const replyButton = item.querySelector('[data-comment-reply]');
    const showRepliesButton = item.querySelector('[data-show-replies]');
    const moreRepliesButton = item.querySelector('[data-more-replies]');
    const communityPage = document.querySelector('.community-page[data-community-manager="true"]');
    if (communityPage && actions && !actions.querySelector('[data-community-comment-delete]')) {
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'comment-link comment-link--danger';
      remove.dataset.communityCommentDelete = '1';
      remove.textContent = 'Xóa';
      remove.addEventListener('click', async () => {
        const ok = await AlouteUI.confirm({ title: 'Xóa bình luận', message: 'Xóa bình luận này khỏi cộng đồng?', okText: 'Xóa' });
        if (!ok) return;
        remove.disabled = true;
        try {
          await api('/api/communities/' + communityPage.dataset.communityId + '/comments/' + encodeURIComponent(commentId), { method: 'DELETE' });
          item.remove();
          toast('Đã xóa bình luận khỏi cộng đồng');
        } catch (err) {
          remove.disabled = false;
          toast(err.message || 'Không thể xóa bình luận', true);
        }
      });
      actions.appendChild(remove);
    }

    if (replyButton) {
      replyButton.addEventListener('click', () => {
        let form = body.querySelector('.comment-reply-form');
        if (form) {
          form.hidden = !form.hidden;
          if (!form.hidden) form.querySelector('input')?.focus();
          return;
        }
        form = document.createElement('form');
        form.className = 'comment-form comment-form--composer comment-reply-form';
        const currentUserAvatar = escapeHtml(document.body.dataset.currentUserAvatar || '/images/avatar_default.png');
        form.innerHTML = '<img class="comment-form__avatar" src="' + currentUserAvatar + '" alt="Ảnh đại diện của bạn" loading="lazy">' +
          '<input type="text" maxlength="500" placeholder="Viết câu trả lời..." aria-label="Viết câu trả lời" required>' +
          '<button type="submit">Gửi</button>';
        actions.insertAdjacentElement('afterend', form);
        form.querySelector('input')?.focus();
        let pendingReplyRequestId = createCommentRequestId();
        form.querySelector('input')?.addEventListener('input', () => {
          pendingReplyRequestId = createCommentRequestId();
        });
        form.addEventListener('submit', async (event) => {
          event.preventDefault();
          const input = form.querySelector('input');
          const content = input.value.trim();
          if (!content) return;
          const submit = form.querySelector('button');
          submit.disabled = true;
          try {
            const created = await api('/api/posts/' + encodeURIComponent(postId) + '/comments', {
              method: 'POST', body: {
                content,
                parentCommentId: commentId,
                clientRequestId: pendingReplyRequestId
              }
            });
            pendingReplyRequestId = createCommentRequestId();
            input.value = '';
            form.hidden = true;
            const isPending = created && String(created.moderationStatus || '').toUpperCase() === 'PENDING';
            if (!isPending) {
              const count = (Number(item.dataset.replyCount) || 0) + 1;
              item.dataset.replyCount = String(count);
              const postEl = item.closest('.post');
              const totalCommentCount = postEl && postEl.querySelector('.action--reply span');
              if (totalCommentCount) {
                const total = (parseInt(totalCommentCount.dataset.raw || totalCommentCount.textContent, 10) || 0) + 1;
                totalCommentCount.dataset.raw = String(total);
                totalCommentCount.textContent = formatCount(total);
              }
              let countButton = item.querySelector('[data-show-replies]');
              if (!countButton) {
                countButton = document.createElement('button');
                countButton.type = 'button';
                countButton.className = 'comment-link';
                countButton.dataset.showReplies = '1';
                actions.appendChild(countButton);
                bindShowReplies(countButton, commentId, repliesContainer, repliesList, moreRepliesButton);
              }
              countButton.textContent = 'Xem ' + count + ' phản hồi';
            } else {
              toast('Câu trả lời đã gửi và đang chờ kiểm duyệt.', false);
            }
            repliesContainer.hidden = false;
            await loadReplies(commentId, repliesContainer, null, true);
          } catch (error) {
            toast(error.message || 'Không gửi được câu trả lời', true);
          } finally {
            submit.disabled = false;
          }
        });
      });
    }

    if (showRepliesButton) {
      bindShowReplies(showRepliesButton, commentId, repliesContainer, repliesList, moreRepliesButton);
    }
    if (moreRepliesButton) {
      moreRepliesButton.addEventListener('click', async () => {
        const next = repliesContainer.dataset.nextCursor || '';
        if (!next || moreRepliesButton.disabled) return;
        moreRepliesButton.disabled = true;
        try { await loadReplies(commentId, repliesContainer, next, false); }
        catch (error) { toast(error.message || 'Không tải thêm phản hồi', true); }
        finally { moreRepliesButton.disabled = false; }
      });
    }
  }

  function bindShowReplies(button, commentId, container, listEl, moreButton) {
    if (!button || button.dataset.bound === '1') return;
    button.dataset.bound = '1';
    button.addEventListener('click', async () => {
      const opening = container.hidden;
      container.hidden = !opening;
      if (!opening) return;
      if (container.dataset.loaded !== '1') {
        button.disabled = true;
        try { await loadReplies(commentId, container, null, true); }
        catch (error) { toast(error.message || 'Không tải được phản hồi', true); }
        finally { button.disabled = false; }
      }
    });
  }

  async function loadReplies(commentId, container, cursor, reset) {
    const listEl = container.querySelector('[data-replies-list]');
    const moreButton = container.querySelector('[data-more-replies]');
    if (reset) {
      listEl.innerHTML = '<span class="comment-list__loading">Đang tải phản hồi...</span>';
      container.dataset.nextCursor = '';
      container.dataset.loaded = '0';
    }
    const params = new URLSearchParams({ size: '20' });
    if (cursor) params.set('cursor', cursor);
    const page = await api('/api/comments/' + encodeURIComponent(commentId) + '/replies?' + params.toString());
    const items = Array.isArray(page && page.items) ? page.items : [];
    if (reset) listEl.innerHTML = '';
    if (!items.length && reset && !page.hasMore) {
      listEl.innerHTML = '<span class="comment-list__empty">Chưa có phản hồi nào.</span>';
    } else if (items.length) {
      listEl.insertAdjacentHTML('beforeend', items.map(comment => commentMarkup(comment, comment.postId)).join(''));
      listEl.querySelectorAll('.comment-item:not([data-actions-bound="1"])').forEach(item => wireCommentItem(item.dataset.postId, item));
    }
    container.dataset.loaded = '1';
    container.dataset.nextCursor = page.nextCursor || '';
    moreButton.hidden = !page.hasMore || !page.nextCursor;
  }

  async function loadComments(postId, listEl, page = 0) {
    if (page === 0) listEl.innerHTML = '<span class="comment-list__loading">Đang tải bình luận...</span>';
    try {
      const comments = await api('/api/posts/' + encodeURIComponent(postId) + '/comments?page=' + page + '&size=50');
      listEl.querySelector('[data-more-comments]')?.remove();
      if (!comments.length && page === 0) {
        listEl.innerHTML = '<span class="comment-list__empty">Chưa có bình luận nào.</span>';
        return;
      }
      if (page === 0) listEl.innerHTML = '';
      listEl.insertAdjacentHTML('beforeend', comments.map(comment => commentMarkup(comment, postId)).join(''));
      listEl.querySelectorAll('.comment-item:not([data-actions-bound="1"])').forEach(item => wireCommentItem(postId, item));
      if (comments.length === 50) {
        const more = document.createElement('button');
        more.type = 'button'; more.className = 'btn-outline';
        more.dataset.moreComments = '1'; more.textContent = 'Tải thêm bình luận';
        more.addEventListener('click', async () => {
          more.disabled = true;
          try { await loadComments(postId, listEl, page + 1); }
          finally { more.disabled = false; }
        });
        listEl.appendChild(more);
      }
    } catch (err) {
      if (page === 0) listEl.innerHTML = '<span class="comment-list__empty">Không tải được bình luận.</span>';
      else toast(err.message, true);
    }
  }

  function initComposePage() {
    const form = document.getElementById('composeForm');
    if (!form) return;

    const audienceValue = document.getElementById('postVisibility');
    const audienceMenu = document.getElementById('postAudienceMenu');
    const audienceTrigger = form.querySelector('[data-audience-open]');
    if (audienceMenu && audienceTrigger && audienceValue) {
      const choices = [...audienceMenu.querySelectorAll('[data-audience-value]')];
      const positionMenu = () => {
        const anchor = audienceTrigger.getBoundingClientRect();
        const box = audienceMenu.getBoundingClientRect();
        const left = Math.max(8, Math.min(anchor.left, window.innerWidth - box.width - 8));
        const top = anchor.bottom + 6 + box.height <= window.innerHeight - 8
          ? anchor.bottom + 6 : Math.max(8, anchor.top - box.height - 6);
        audienceMenu.style.left = left + 'px';
        audienceMenu.style.top = top + 'px';
      };
      const openMenu = () => {
        choices.forEach(choice => choice.setAttribute('aria-selected', String(choice.dataset.audienceValue === audienceValue.value)));
        audienceMenu.showPopover();
        audienceTrigger.setAttribute('aria-expanded', 'true');
        positionMenu();
        choices.find(choice => choice.dataset.audienceValue === audienceValue.value)?.focus();
      };
      const closeMenu = () => {
        audienceMenu.hidePopover();
        audienceTrigger.setAttribute('aria-expanded', 'false');
      };
      audienceTrigger.addEventListener('keydown', event => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        event.preventDefault();
        openMenu();
      });
      choices.forEach(choice => choice.addEventListener('click', () => {
        audienceValue.value = choice.dataset.audienceValue;
        choices.forEach(option => option.setAttribute('aria-selected', String(option === choice)));
        audienceTrigger.querySelector('[data-audience-current-label]').textContent = choice.getAttribute('aria-label');
        audienceTrigger.querySelector('[data-audience-current-icon]').innerHTML = postAudienceMarkup(audienceValue.value);
        audienceTrigger.setAttribute('aria-label', 'Ai có thể xem bài viết: ' + choice.getAttribute('aria-label'));
        audienceValue.dispatchEvent(new Event('change', { bubbles: true }));
        closeMenu();
        audienceTrigger.focus();
      }));
      audienceMenu.addEventListener('keydown', event => {
        const index = choices.indexOf(document.activeElement);
        let next;
        if (event.key === 'ArrowDown') next = (index + 1) % choices.length;
        else if (event.key === 'ArrowUp') next = (index + choices.length - 1) % choices.length;
        else if (event.key === 'Home') next = 0;
        else if (event.key === 'End') next = choices.length - 1;
        else if (event.key === 'Escape' || event.key === 'Tab') {
          closeMenu();
          if (event.key === 'Escape') { event.preventDefault(); audienceTrigger.focus(); }
          return;
        } else return;
        event.preventDefault();
        choices[next].focus();
      });
      audienceMenu.addEventListener('toggle', () => {
        const isOpen = audienceMenu.matches(':popover-open');
        audienceTrigger.setAttribute('aria-expanded', String(isOpen));
        if (isOpen) {
          positionMenu();
          choices.find(choice => choice.dataset.audienceValue === audienceValue.value)?.focus();
        }
      });
      window.addEventListener('resize', () => { if (audienceMenu.matches(':popover-open')) positionMenu(); });
      window.addEventListener('scroll', () => { if (audienceMenu.matches(':popover-open')) positionMenu(); }, true);
    }

    const textarea = document.getElementById('composeContent');
    const fileInput = document.getElementById('composeImageInput');
    const videoInput = document.getElementById('composeVideoInput');
    const preview = document.getElementById('composePreview');
    const submitBtn = document.getElementById('composeSubmit');

    const refreshComposeState = () => {
      submitBtn.disabled = !textarea.value.trim() && !fileInput.files[0] && !videoInput.files[0];
    };

    textarea.addEventListener('input', refreshComposeState);

    fileInput.addEventListener('change', () => {
      const file = fileInput.files[0];
      if (!file) return;
      videoInput.value = '';
      refreshComposeState();
      const reader = new FileReader();
      reader.onload = () => {
        preview.innerHTML =
          '<div class="compose-page__preview"><img src="' + reader.result + '">' +
          '<button type="button" id="removeImageBtn" aria-label="Xoa anh">&times;</button></div>';
        document.getElementById('removeImageBtn').addEventListener('click', () => {
          fileInput.value = '';
          preview.innerHTML = '';
          refreshComposeState();
        });
      };
      reader.readAsDataURL(file);
    });

    videoInput.addEventListener('change', () => {
      const file = videoInput.files[0];
      if (!file) return;
      fileInput.value = '';
      refreshComposeState();
      const objectUrl = URL.createObjectURL(file);
      preview.innerHTML =
        '<div class="compose-page__preview"><video src="' + objectUrl + '" controls preload="metadata"></video>' +
        '<button type="button" id="removeVideoBtn" aria-label="Xóa video">&times;</button></div>';
      document.getElementById('removeVideoBtn').addEventListener('click', () => {
        URL.revokeObjectURL(objectUrl);
        videoInput.value = '';
        preview.innerHTML = '';
        refreshComposeState();
      });

      stompClient.subscribe('/user/queue/message-deletions', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:message-deleted', { detail: payload }));
      });
    });

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const content = textarea.value.trim();
      if (!content && !fileInput.files[0] && !videoInput.files[0]) return;

      submitBtn.disabled = true;
      submitBtn.textContent = 'Dang dang...';
      try {
        let imageUrl = null;
        let videoUrl = null;
        if (fileInput.files[0]) {
          const fd = new FormData();
          fd.append('file', fileInput.files[0]);
          fd.append('purpose', 'post');
          const res = await api('/api/upload/image', { method: 'POST', body: fd });
          imageUrl = res.url;
        }
        if (videoInput.files[0]) {
          const fd = new FormData();
          fd.append('file', videoInput.files[0]);
          fd.append('purpose', 'post');
          const res = await api('/api/upload/media', { method: 'POST', body: fd });
          if (res.type !== 'video') throw new Error('Tệp đã chọn không phải video hợp lệ.');
          videoUrl = res.url;
        }
        const communityId = String(form.dataset.communityId || '').trim();
        const postBody = { content, imageUrl, videoUrl };
        let endpoint = '/api/posts';
        if (communityId) {
          endpoint = '/api/communities/' + encodeURIComponent(communityId) + '/posts';
        } else {
          postBody.visibility = document.getElementById('postVisibility')?.value || 'PUBLIC';
        }
        const created = await api(endpoint, { method: 'POST', body: postBody });
        const moderationStatus = String(created?.moderationStatus || '').toUpperCase();
        const communityStatus = String(created?.communityPostStatus || '').toUpperCase();
        if (communityId) {
          if (communityStatus === 'PENDING' && moderationStatus === 'PENDING') {
            toast('Bài viết đã gửi và đang chờ cả hệ thống kiểm duyệt lẫn chủ nhóm/quản lý duyệt.');
          } else if (communityStatus === 'PENDING') {
            toast('Bài viết đã gửi và đang chờ chủ nhóm/quản lý duyệt.');
          } else if (moderationStatus === 'PENDING') {
            toast('Bài viết đã gửi và đang chờ hệ thống kiểm duyệt.');
          } else if (moderationStatus === 'FLAGGED') {
            toast('Moderation đã đánh dấu bài viết để theo dõi bổ sung. Bài viết không cần chờ ADMIN duyệt.');
          } else if (moderationStatus === 'HIDDEN') {
            toast('Bài viết đang bị ẩn sau khi kiểm duyệt.', true);
          } else {
            await showNewCommunityPost(communityId, created && created.id);
          }
          textarea.value = '';
          fileInput.value = '';
          videoInput.value = '';
          preview.replaceChildren();
          refreshComposeState();
          submitBtn.textContent = 'Đăng';
          return;
        }
        if (moderationStatus === 'PENDING') {
          queueFlashToast('Đã gửi bài viết. Hệ thống đang kiểm duyệt nội dung; trong lúc chờ, bài viết chỉ hiển thị với bạn.', false);
        } else if (moderationStatus === 'FLAGGED') {
          queueFlashToast('Moderation đã đánh dấu bài viết để theo dõi bổ sung. Bài viết không cần chờ ADMIN duyệt.', false);
        } else if (moderationStatus === 'HIDDEN') {
          queueFlashToast('Bài viết bị ẩn sau khi kiểm duyệt. Bạn có thể sửa nội dung rồi gửi lại.', true);
        } else {
          queueFlashToast('Đã đăng bài viết.', false);
        }
        window.location.href = '/feed';
      } catch (err) {
        toast(err.message, true);
        submitBtn.disabled = false;
        submitBtn.textContent = 'Đăng';
      }
    });
  }

  function initFollowButtons() {
    document.querySelectorAll('.btn-follow[data-user-id]').forEach(btn => {
      if (btn.dataset.bound) return;
      btn.dataset.bound = '1';
      // Respect the server-rendered/search result state before the next click.
      if (!btn.dataset.followStatus) {
        btn.dataset.followStatus = btn.classList.contains('is-following') ? 'FOLLOWING'
          : (/Hủy yêu cầu|Đã gửi yêu cầu/.test(btn.textContent || '') ? 'PENDING' : 'NONE');
      }
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const userId = btn.dataset.userId;
        btn.disabled = true;
        try {
          await api('/api/users/' + encodeURIComponent(userId) + '/follow', { method: 'POST' });
          const status = await api('/api/users/' + encodeURIComponent(userId) + '/follow-status');
          applyFollowStatus(btn, status);
          if (status === 'PENDING') {
            toast('Đã gửi yêu cầu theo dõi. Bạn có thể hủy yêu cầu bằng nút này.', false);
          }
          document.dispatchEvent(new CustomEvent('aloute:follow-request-updated', {
            detail: { targetId: Number(userId), status: status }
          }));
          if (btn.closest('.profile-cover')?.querySelector('.profile-cover__badge')) location.reload();
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      });
    });
  }

  function applyFollowStatus(btn, status) {
    if (!btn) return;
    const normalized = String(status || 'NONE').toUpperCase();
    btn.dataset.followStatus = ['FOLLOWING', 'PENDING'].includes(normalized) ? normalized : 'NONE';
    btn.classList.toggle('is-following', normalized === 'FOLLOWING');
    btn.classList.toggle('is-pending', normalized === 'PENDING');
    if (normalized === 'FOLLOWING') btn.textContent = 'Đang theo dõi';
    else if (normalized === 'PENDING') btn.textContent = 'Hủy yêu cầu';
    else btn.textContent = 'Theo dõi';
  }

  function applyFollowState(btn, following) {
    applyFollowStatus(btn, following ? 'FOLLOWING' : 'NONE');
  }

  function initCloseFriendButtons() {
    document.querySelectorAll('.btn-close-friend[data-user-id]').forEach(btn => {
      if (btn.dataset.bound) return;
      btn.dataset.bound = '1';
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        handleCloseFriendClick(btn);
      });
    });

    document.querySelectorAll('.close-friend-request-bar[data-user-id]').forEach(bar => {
      if (bar.dataset.bound) return;
      bar.dataset.bound = '1';
      const userId = bar.dataset.userId;
      const acceptBtn = bar.querySelector('.close-friend-request-bar__accept');
      const declineBtn = bar.querySelector('.close-friend-request-bar__decline');

      if (acceptBtn) {
        acceptBtn.addEventListener('click', async () => {
          acceptBtn.disabled = true;
          if (declineBtn) declineBtn.disabled = true;
          try {
            await api('/api/close-friends/' + userId + '/accept', { method: 'POST' });
            toast('Đã chấp nhận kết bạn thân');
            location.reload();
          } catch (err) {
            toast(err.message, true);
            acceptBtn.disabled = false;
            if (declineBtn) declineBtn.disabled = false;
          }
        });
      }
      if (declineBtn) {
        declineBtn.addEventListener('click', async () => {
          if (!(await AlouteUI.confirm({ title: 'Từ chối lời mời', message: 'Từ chối lời mời kết bạn thân này?', okText: 'Từ chối' }))) return;
          if (acceptBtn) acceptBtn.disabled = true;
          declineBtn.disabled = true;
          try {
            await api('/api/close-friends/' + userId, { method: 'DELETE' });
            toast('Đã từ chối lời mời');
            location.reload();
          } catch (err) {
            toast(err.message, true);
            if (acceptBtn) acceptBtn.disabled = false;
            declineBtn.disabled = false;
          }
        });
      }
    });
  }

  async function handleCloseFriendClick(btn) {
    const userId = btn.dataset.userId;
    const status = btn.dataset.status || 'NONE';
    btn.disabled = true;
    try {
      if (status === 'NONE') {
        const result = await api('/api/close-friends/' + userId + '/request', { method: 'POST' });
        applyCloseFriendState(btn, result);
        toast(result === 'ACCEPTED' ? 'Đã trở thành bạn thân' : 'Đã gửi lời mời kết bạn thân');
      } else if (status === 'PENDING_SENT') {
        if (!(await AlouteUI.confirm({ title: 'Hủy lời mời', message: 'Hủy lời mời kết bạn thân đã gửi?', okText: 'Hủy lời mời' }))) return;
        await api('/api/close-friends/' + userId, { method: 'DELETE' });
        applyCloseFriendState(btn, 'NONE');
        toast('Đã hủy lời mời');
      } else if (status === 'PENDING_RECEIVED') {
        await api('/api/close-friends/' + userId + '/accept', { method: 'POST' });
        applyCloseFriendState(btn, 'ACCEPTED');
        toast('Đã chấp nhận kết bạn thân');
      } else if (status === 'ACCEPTED') {
        if (!(await AlouteUI.confirm({ title: 'Xóa bạn thân', message: 'Xóa khỏi danh sách bạn thân?', okText: 'Xóa' }))) return;
        await api('/api/close-friends/' + userId, { method: 'DELETE' });
        applyCloseFriendState(btn, 'NONE');
        toast('Đã xóa khỏi bạn thân');
      }
      if (btn.closest('.profile-cover')) location.reload();
    } catch (err) {
      toast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  function applyCloseFriendState(btn, status) {
    btn.dataset.status = status;
    btn.classList.remove('is-close', 'is-pending');
    if (status === 'ACCEPTED') {
      btn.classList.add('is-close');
      btn.textContent = '★ Bạn thân';
    } else if (status === 'PENDING_SENT') {
      btn.classList.add('is-pending');
      btn.textContent = 'Đã gửi lời mời';
    } else if (status === 'PENDING_RECEIVED') {
      btn.textContent = 'Chấp nhận bạn thân';
    } else {
      btn.textContent = '+ Bạn thân';
    }
  }

  function initSearchPage() {
    const input = document.getElementById('searchInput');
    const resultsEl = document.getElementById('searchResults');
    if (!input || !resultsEl) return;

    const bar = document.getElementById('searchBar');
    const clearBtn = document.getElementById('searchClear');
    const tabsEl = document.getElementById('searchTabs');
    const discoverEl = document.getElementById('searchDiscover');

    const cache = { users: Object.create(null), posts: Object.create(null) };
    let currentTab = 'users';
    let keyword = '';
    let debounceTimer = null;
    let requestToken = 0;
    let searchController = null;
    let searchLoader = null;

    pageDisposers.add(() => {
      clearTimeout(debounceTimer);
      cancelSearch();
    });

    function cancelSearch() {
      requestToken++;
      if (searchController) searchController.abort();
      searchController = null;
      if (searchLoader) searchLoader.dispose();
      searchLoader = null;
    }

    loadDiscoverSections();

    if (bar) {
      input.addEventListener('focus', () => bar.classList.add('is-focused'));
      input.addEventListener('blur', () => bar.classList.remove('is-focused'));
    }

    input.addEventListener('input', () => {
      cancelSearch();
      const value = input.value.trim();
      if (clearBtn) clearBtn.hidden = value.length === 0;
      clearTimeout(debounceTimer);
      if (!value) {
        keyword = '';
        showDiscover();
        return;
      }
      debounceTimer = setTimeout(() => {
        keyword = value;
        runSearch();
      }, 300);
    });

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        resetSearch();
      } else if (e.key === 'Enter') {
        clearTimeout(debounceTimer);
        const value = input.value.trim();
        if (value) {
          keyword = value;
          runSearch();
        }
      }
    });

    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        resetSearch();
        input.focus();
      });
    }

    if (tabsEl) {
      tabsEl.querySelectorAll('.search-tab').forEach(tab => {
        tab.addEventListener('click', () => {
          if (tab.dataset.tab === currentTab) return;
          currentTab = tab.dataset.tab;
          setActiveTab(currentTab);
          clearTimeout(debounceTimer);
          keyword = input.value.trim();
          if (keyword || currentTab === 'posts') runSearch();
          else showDiscover();
        });
      });
    }

    if (discoverEl) {
      discoverEl.addEventListener('click', (e) => {
        if (e.target.closest('.btn-follow') || e.target.closest('a')) return;
        const trigger = e.target.closest('[data-search-query]');
        if (!trigger) return;

        const query = trigger.dataset.searchQuery || '';
        if (!query) return;

        clearTimeout(debounceTimer);
        input.value = query;
        keyword = query.trim();
        if (clearBtn) clearBtn.hidden = false;
        currentTab = 'posts';
        setActiveTab(currentTab);
        runSearch();
        window.scrollTo({ top: 0, behavior: 'smooth' });
      });
    }

    function setActiveTab(name) {
      if (!tabsEl) return;
      tabsEl.querySelectorAll('.search-tab').forEach(t => {
        t.classList.toggle('is-active', t.dataset.tab === name);
      });
    }

    function resetSearch() {
      clearTimeout(debounceTimer);
      input.value = '';
      keyword = '';
      if (clearBtn) clearBtn.hidden = true;
      showDiscover();
    }

    function showDiscover() {
      if (currentTab === 'posts') {
        runSearch();
        return;
      }
      cancelSearch();
      resultsEl.hidden = true;
      resultsEl.innerHTML = '';
      if (tabsEl) tabsEl.hidden = false;
      if (discoverEl) discoverEl.hidden = false;
    }

    function showResults() {
      if (discoverEl) discoverEl.hidden = true;
      if (tabsEl) tabsEl.hidden = false;
      resultsEl.hidden = false;
    }

    const SEARCH_PAGE_SIZE = 20;

    async function runSearch(page = 0, append = false) {
      if (append && searchController) return;
      cancelSearch();
      showResults();

      const tab = currentTab;
      const key = keyword;
      const browsingPosts = tab === 'posts' && !key;
      const token = ++requestToken;

      // A fresh query revalidates visibility; cached items only preserve pagination state.
      const controller = new AbortController();
      searchController = controller;
      const moreButton = append ? resultsEl.querySelector('.search-load-more') : null;
      if (moreButton) {
        moreButton.disabled = true;
        moreButton.textContent = 'Đang tải bài viết…';
        moreButton.setAttribute('aria-busy', 'true');
      }

      if (!append) resultsEl.innerHTML = searchSkeleton(tab);
      try {
        // Reuse the authorized cursor feed for an empty Posts tab; search APIs keep their contract.
        const cursor = append && cache.posts[key] ? cache.posts[key].nextCursor : null;
        const url = browsingPosts
          ? '/api/posts/feed/cursor?tab=foryou&size=' + SEARCH_PAGE_SIZE
            + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '')
          : (tab === 'posts' ? '/api/search/posts?q=' : '/api/search/users?q=')
            + encodeURIComponent(key) + '&page=' + page + '&size=' + SEARCH_PAGE_SIZE;
        const data = await api(url, { signal: controller.signal });
        if (token !== requestToken) return;
        const batch = browsingPosts ? (data?.items || []) : (data || []);
        if (browsingPosts && data?.hasMore && (!data.nextCursor || data.nextCursor === cursor)) {
          throw new Error('Chưa tải được các bài tiếp theo. Vui lòng thử lại.');
        }
        const existing = append && cache[tab][key] ? cache[tab][key].items : [];
        const seen = new Set(existing.map(item => item.id));
        const added = batch.filter(item => {
          if (seen.has(item.id)) return false;
          seen.add(item.id);
          return true;
        });
        cache[tab][key] = {
          items: existing.concat(added),
          page,
          hasMore: browsingPosts ? Boolean(data?.hasMore && data?.nextCursor) : batch.length === SEARCH_PAGE_SIZE,
          nextCursor: browsingPosts ? data?.nextCursor : null
        };
        const keys = Object.keys(cache[tab]);
        if (keys.length > 20) {
          // Integer-like query keys sort before other object keys; keep the active page state.
          const removable = keys.find(cachedKey => cachedKey !== key);
          if (removable !== undefined) delete cache[tab][removable];
        }
        paint(tab, cache[tab][key].items, cache[tab][key].hasMore, page, append, added);
      } catch (err) {
        if (controller.signal.aborted || token !== requestToken) return;
        if (append && moreButton) {
          moreButton.textContent = 'Thử tải lại';
          toast(err.message, true);
        } else resultsEl.innerHTML = discoverEmpty('Không tải được kết quả', err.message);
      } finally {
        if (moreButton) {
          moreButton.disabled = false;
          moreButton.removeAttribute('aria-busy');
        }
        if (searchController === controller) searchController = null;
      }
    }

    function paint(tab, items, hasMore, page, append = false, added = []) {
      resultsEl.querySelector('.search-load-more')?.remove();
      if (!items.length && !hasMore) {
        resultsEl.innerHTML = tab === 'posts'
          ? (keyword
            ? discoverEmpty('Chưa có bài viết nào khớp', 'Thử một từ khóa ngắn hơn hoặc kiểm tra lại chính tả.')
            : discoverEmpty('Chưa có bài viết nào', 'Các bài viết bạn có quyền xem sẽ xuất hiện ở đây.'))
          : discoverEmpty('Không tìm thấy ai', 'Bạn có thể tìm theo họ tên hoặc @username.');
        return;
      }

      if (tab === 'posts') {
        const panel = append ? resultsEl.querySelector('.feed-panel') : null;
        if (panel) panel.insertAdjacentHTML('beforeend', added.map(renderSearchPostCard).join(''));
        else resultsEl.innerHTML = '<div class="feed-panel">' + items.map(renderSearchPostCard).join('') + '</div>';
        initFeedInteractions();
        formatInitialCounts();
      } else {
        resultsEl.innerHTML =
          '<div class="discover-card"><div class="discover-card__body">' +
          items.map(renderUserRow).join('') +
          '</div></div>';
        initFollowButtons();
      }
      if (hasMore) {
        const more = document.createElement('button');
        more.type = 'button';
        more.className = 'btn btn-outline search-load-more';
        more.textContent = 'Xem thêm kết quả';
        more.addEventListener('click', () => runSearch(page + 1, true));
        resultsEl.appendChild(more);
        if (tab === 'posts') searchLoader = watchLoadMore(more, () => runSearch(page + 1, true));
      } else if (tab === 'posts' && items.length) {
        const end = document.createElement('p');
        end.className = 'post-pagination__status';
        end.setAttribute('role', 'status');
        end.textContent = 'Bạn đã xem hết bài viết.';
        resultsEl.appendChild(end);
      }
    }
  }

  async function loadDiscoverSections() {
    const suggestionList = document.getElementById('suggestionList');
    const trendingList = document.getElementById('trendingList');

    if (suggestionList) {
      try {
        const users = await api('/api/search/suggestions?limit=8');
        suggestionList.innerHTML = (users && users.length)
          ? users.map(renderSuggestionRow).join('')
          : discoverEmpty('Chưa có gợi ý nào', 'Hãy quay lại sau khi có thêm người dùng mới nhé.');
        initFollowButtons();
      } catch (err) {
        suggestionList.innerHTML = discoverEmpty('Không tải được gợi ý', err.message);
      }
    }

    if (trendingList) {
      try {
        const topics = await api('/api/search/trending?limit=8');
        trendingList.innerHTML = (topics && topics.length)
          ? topics.map(renderTrendingRow).join('')
          : discoverEmpty('Chưa có xu hướng nào', 'Đăng bài kèm #hashtag để bắt đầu một chủ đề.');
      } catch (err) {
        trendingList.innerHTML = discoverEmpty('Không tải được xu hướng', err.message);
      }
    }
  }

  const DEFAULT_AVATAR = '/images/avatar_default.png';

  function renderSuggestionRow(u) {
    const avatar = escapeHtml(u.avatarUrl || DEFAULT_AVATAR);
    const reason = u.suggestionReason || 'Có thể bạn quen';
    const followers = u.followerCount > 0 ? formatCount(u.followerCount) + ' người theo dõi' : '';

    return (
      '<div class="discover-user">' +
      '  <a class="discover-user__main" href="' + profileUrl(u.username, u.id) + '">' +
      '    <img class="discover-user__avatar" src="' + avatar + '" alt="avatar">' +
      '    <div class="discover-user__info">' +
      '      <div class="discover-user__name">' + escapeHtml(u.fullName || u.username) + '</div>' +
      '      <div class="discover-user__username">@' + escapeHtml(u.username) + '</div>' +
      '      <div class="discover-user__meta">' +
      '        <span class="discover-user__reason">' + escapeHtml(reason) + '</span>' +
      (followers ? '<span class="discover-user__dot">·</span><span>' + followers + '</span>' : '') +
      '      </div>' +
      '    </div>' +
      '  </a>' +
      '  <button class="btn-follow' + (u.followedByCurrentUser ? ' is-following' : (u.followRequestPending ? ' is-pending' : '')) + '" data-user-id="' + u.id + '" data-follow-status="' + (u.followedByCurrentUser ? 'FOLLOWING' : (u.followRequestPending ? 'PENDING' : 'NONE')) + '">' +
      (u.followedByCurrentUser ? 'Đang theo dõi' : (u.followRequestPending ? 'Hủy yêu cầu' : 'Theo dõi')) +
      '</button>' +
      '</div>'
    );
  }

  function renderTrendingRow(topic, index) {
    const thumb = topic.imageUrl
      ? '<img class="trend__thumb" src="' + escapeHtml(topic.imageUrl) + '" alt="">'
      : '<div class="trend__thumb trend__thumb--empty"><span>' + escapeHtml((topic.topic || '#').charAt(0).toUpperCase()) + '</span></div>';

    return (
      '<div class="trend" role="button" tabindex="0" data-search-query="' + escapeHtml(topic.query || topic.topic || '') + '">' +
      '  <div class="trend__rank">' + (index + 1) + '</div>' +
      '  <div class="trend__body">' +
      '    <div class="trend__topic">' + escapeHtml(topic.topic || '') + '</div>' +
      (topic.description ? '<div class="trend__desc">' + escapeHtml(topic.description) + '</div>' : '') +
      '    <div class="trend__meta">' + escapeHtml(topic.meta || '') + '</div>' +
      '  </div>' +
      thumb +
      '</div>'
    );
  }

  function postAudienceMarkup(visibility) {
    const audience = visibility || 'PUBLIC';
    const icons = {
      PUBLIC: ['Công khai', '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18M5 6.5h14M5 17.5h14"/>'],
      FRIENDS: ['Bạn bè', '<circle cx="9" cy="8" r="4"/><circle cx="18" cy="9" r="3"/><path d="M1 21v-3a8 8 0 0 1 16 0v3zm17 0v-3a9 9 0 0 0-1.5-5 6 6 0 0 1 6.5 6v2z"/>'],
      CLOSE_FRIENDS: ['Bạn thân', '<circle cx="12" cy="12" r="9"/><path fill="currentColor" stroke="none" d="m12 5 2.1 4.3 4.7.7-3.4 3.3.8 4.7-4.2-2.2-4.2 2.2.8-4.7L5.2 10l4.7-.7z"/>'],
      ONLY_ME: ['Chỉ mình tôi', '<path d="M6 10V7a6 6 0 0 1 12 0v3h1a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zm2 0h8V7a4 4 0 0 0-8 0zm3 5v3h2v-3z"/>']
    };
    if (!Object.prototype.hasOwnProperty.call(icons, audience)) return '';
    const [label, paths] = icons[audience];
    return '<span class="post__audience" role="img" data-post-audience="' + audience + '" aria-label="' + label + '" title="' + label + '">' +
      '<svg viewBox="0 0 24 24" aria-hidden="true" ' + (audience === 'PUBLIC' || audience === 'CLOSE_FRIENDS' ? 'fill="none" stroke="currentColor" stroke-width="1.8"' : 'fill="currentColor"') + '>' + paths + '</svg></span>';
  }

  function renderVisibilityBadge(visibility, moderationStatus) {
    const moderation = moderationStatus == null ? '' : String(moderationStatus).trim().toUpperCase();
    // Unknown moderation state must fail closed too. Only an explicit APPROVED state may
    // display the audience/privacy icon; missing API data must never silently imply PUBLIC.
    if (!moderation) {
      return '<span class="post__moderation-status post__moderation-status--pending" title="Chưa xác minh trạng thái kiểm duyệt" aria-label="Chưa xác minh trạng thái kiểm duyệt"><i class="bi bi-hourglass-split" aria-hidden="true"></i><span>Chưa xác minh</span></span>';
    }
    // PENDING is visible only to the author; HIDDEN is hidden. A successfully evaluated
    // FLAGGED item still follows the post's audience/privacy setting and is not an admin gate.
    if (moderation === 'PENDING') {
      return '<span class="post__moderation-status post__moderation-status--pending" title="Bài viết chỉ hiển thị với bạn trong lúc chờ kiểm duyệt" aria-label="Đang kiểm duyệt, chỉ bạn thấy"><i class="bi bi-hourglass-split" aria-hidden="true"></i><span>Đang kiểm duyệt</span></span>';
    }
    if (moderation === 'FLAGGED') {
      return '<span class="post__moderation-status post__moderation-status--flagged" title="Moderation đã đánh dấu nội dung để theo dõi bổ sung; không mặc định chờ ADMIN" aria-label="Moderation đã đánh dấu để theo dõi bổ sung"><i class="bi bi-shield-exclamation" aria-hidden="true"></i><span>Đã đánh dấu</span></span>';
    }
    if (moderation === 'HIDDEN') {
      return '<span class="post__moderation-status post__moderation-status--hidden" title="Bài viết đã bị ẩn sau khi kiểm duyệt" aria-label="Bài viết đã bị ẩn"><i class="bi bi-eye-slash" aria-hidden="true"></i><span>Đã ẩn</span></span>';
    }

    const status = String(visibility || 'PUBLIC').toUpperCase();
    if (status === 'PUBLIC') {
      return '<span class="post__visibility" title="Công khai" aria-label="Bài viết công khai"><i class="bi bi-globe2" aria-hidden="true"></i></span>';
    }
    if (status === 'FRIENDS') {
      return '<span class="post__visibility post__visibility--private" title="Chế độ Bạn bè" aria-label="Bài viết theo chế độ Bạn bè"><i class="bi bi-people-fill" aria-hidden="true"></i></span>';
    }
    if (status === 'CLOSE_FRIENDS') {
      return '<span class="post__visibility post__visibility--private" title="Bạn thân" aria-label="Bài viết dành cho bạn thân"><i class="bi bi-star-fill" aria-hidden="true"></i></span>';
    }
    if (status === 'ONLY_ME') {
      return '<span class="post__visibility post__visibility--private" title="Chỉ mình tôi" aria-label="Bài viết chỉ mình tôi"><i class="bi bi-lock-fill" aria-hidden="true"></i></span>';
    }
    return '';
  }

  function renderSearchPostCard(post) {
    const avatar = escapeHtml(post.userAvatarUrl || DEFAULT_AVATAR);
    const when = escapeHtml(post.timeAgo || timeAgo(post.createdAt));
    const actionPostId = escapeHtml(post.interactionPostId || post.id);

    return (
      '<div class="post" data-post-id="' + escapeHtml(post.id) + '" data-action-post-id="' + actionPostId + '">' +
      '  <div class="post__avatar-col">' +
      '    <a href="' + profileUrl(post.username, post.userId) + '"><img class="post__avatar" src="' + avatar + '" alt="avatar"></a>' +
      '  </div>' +
      '  <div class="post__body">' +
      '    <div class="post__header">' +
      '      <a href="' + profileUrl(post.username, post.userId) + '" class="post__username">' + escapeHtml(post.username || '') + '</a>' +
      '      <span class="post__time">' + when + '</span>' +
      renderVisibilityBadge(post.visibility, post.moderationStatus) +
      '    </div>' +
      '    <p class="post__content">' + escapeHtml(post.content || '') + '</p>' +
      (post.imageUrl ? '<img class="post__image" src="' + escapeHtml(post.imageUrl) + '" alt="ảnh bài viết">' : '') +
      (post.videoUrl ? '<video class="post__video" src="' + escapeHtml(post.videoUrl) + '" controls preload="none"></video>' : '') +
      '    <div class="post__actions">' +
      '      <button class="action action--like' + (post.likedByCurrentUser ? ' is-liked' : '') + '">' +
      '        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20.2s-7.2-4.4-9-9C1.7 7.6 3.2 5 6 4.6c1.9-.3 3.4.6 4.3 1.9.6-.9 1.5-1.6 2.5-1.9 2.9-.6 4.9 2 4.5 4.6-.7 4.7-9.3 10.5-9.3 10.5Z"/></svg>' +
      '        <span data-raw="' + (post.likeCount || 0) + '">' + (post.likeCount || 0) + '</span>' +
      '      </button>' +
      '      <button class="action action--reply" data-post-id="' + actionPostId + '">' +
      '        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.4 8.4 0 0 1-8.6 8.4c-1.3 0-2.6-.3-3.7-.9L3 20l1.1-5.4a8.3 8.3 0 0 1-.7-3.1A8.4 8.4 0 0 1 12.1 3a8.4 8.4 0 0 1 8.9 8.5Z"/></svg>' +
      '        <span data-raw="' + (post.commentCount || 0) + '">' + (post.commentCount || 0) + '</span>' +
      '      </button>' +
      '    </div>' +
      '  </div>' +
      '</div>'
    );
  }

  function discoverEmpty(title, hint) {
    return (
      '<div class="discover-empty">' +
      '  <p>' + escapeHtml(title) + '</p>' +
      (hint ? '<span>' + escapeHtml(hint) + '</span>' : '') +
      '</div>'
    );
  }

  function searchSkeleton(tab) {
    const row = tab === 'posts'
      ? '<div class="skeleton-row"><div class="skeleton skeleton--avatar"></div><div class="skeleton-row__lines">' +
        '<div class="skeleton skeleton--line" style="width:35%"></div>' +
        '<div class="skeleton skeleton--line" style="width:90%"></div>' +
        '<div class="skeleton skeleton--line" style="width:65%"></div></div></div>'
      : '<div class="skeleton-row"><div class="skeleton skeleton--avatar"></div><div class="skeleton-row__lines">' +
        '<div class="skeleton skeleton--line" style="width:42%"></div>' +
        '<div class="skeleton skeleton--line" style="width:28%"></div></div>' +
        '<div class="skeleton skeleton--pill"></div></div>';

    return '<div class="discover-card"><div class="discover-card__body">' + row + row + row + '</div></div>';
  }

  function renderUserRow(u) {
    const followStatus = u.followedByCurrentUser ? 'FOLLOWING' : (u.followRequestPending ? 'PENDING' : 'NONE');
    const followLabel = followStatus === 'FOLLOWING' ? 'Đang theo dõi' : (followStatus === 'PENDING' ? 'Hủy yêu cầu' : 'Theo dõi');
    const followClasses = followStatus === 'FOLLOWING' ? ' is-following' : (followStatus === 'PENDING' ? ' is-pending' : '');
    const followBtn = '<button class="btn-follow' + followClasses + '" data-user-id="' + u.id + '" data-follow-status="' + followStatus + '">' + followLabel + '</button>';

    return (
      '<div class="discover-user">' +
      '  <a class="discover-user__main" href="' + profileUrl(u.username, u.id) + '">' +
      '    <img class="discover-user__avatar" src="' + escapeHtml(u.avatarUrl || DEFAULT_AVATAR) + '" alt="avatar">' +
      '    <div class="discover-user__info">' +
      '      <div class="discover-user__name">' + escapeHtml(u.fullName || u.username) + '</div>' +
      '      <div class="discover-user__username">@' + escapeHtml(u.username) + '</div>' +
      (u.bio ? '<div class="discover-user__bio">' + escapeHtml(u.bio) + '</div>' : '') +
      '    </div>' +
      '  </a>' +
      '  <div class="discover-user__actions">' + followBtn + '</div>' +
      '</div>'
    );
  }

  const PEOPLE_TABS = {
    followers: { title: 'Người theo dõi', url: id => '/api/users/' + id + '/followers',
                 empty: 'Chưa có ai theo dõi.' },
    following: { title: 'Đang theo dõi', url: id => '/api/users/' + id + '/following',
                 empty: 'Chưa theo dõi ai.' }
  };

  function initProfilePeoplePanel() {
    const statsEl = document.querySelector('.profile-stats');
    const modal = document.getElementById('peopleModal');
    const body = document.getElementById('peopleModalBody');
    if (!statsEl || !modal || !body) return;

    const profileUserId = statsEl.dataset.profileUserId;
    const tabButtons = Array.from(modal.querySelectorAll('.people-tab'));
    const cache = {};
    let currentTab = null;
    let requestToken = 0;

    statsEl.querySelectorAll('[data-people-tab]').forEach(btn => {
      btn.addEventListener('click', () => openModal(btn.dataset.peopleTab));
    });

    tabButtons.forEach(btn => {
      btn.addEventListener('click', () => selectTab(btn.dataset.tab));
    });

    modal.querySelectorAll('[data-people-close]').forEach(el => {
      el.addEventListener('click', closeModal);
    });

    if (!window.__aloutePeopleEscapeBound) {
      window.__aloutePeopleEscapeBound = true;
      document.addEventListener('keydown', (e) => {
        const activeModal = document.getElementById('peopleModal');
        if (e.key === 'Escape' && activeModal && !activeModal.hidden) {
          activeModal.hidden = true;
          document.body.classList.remove('no-scroll');
        }
      });
    }

    function openModal(tab) {
      modal.hidden = false;
      document.body.classList.add('no-scroll');
      selectTab(tab);
    }

    function closeModal() {
      modal.hidden = true;
      document.body.classList.remove('no-scroll');
    }

    async function selectTab(tab) {
      if (!PEOPLE_TABS[tab]) return;
      currentTab = tab;
      tabButtons.forEach(b => b.classList.toggle('is-active', b.dataset.tab === tab));

      const token = ++requestToken;

      if (cache[tab]) {
        paint(tab, cache[tab]);
        return;
      }

      body.innerHTML = peopleSkeleton();
      try {
        const users = await api(PEOPLE_TABS[tab].url(profileUserId));
        if (token !== requestToken || currentTab !== tab) return;
        cache[tab] = users || [];
        paint(tab, cache[tab]);
      } catch (err) {
        if (token !== requestToken) return;
        body.innerHTML = discoverEmpty('Không tải được danh sách', err.message);
      }
    }

    function paint(tab, users) {
      if (!users.length) {
        body.innerHTML = discoverEmpty(PEOPLE_TABS[tab].title, PEOPLE_TABS[tab].empty);
        return;
      }
      body.innerHTML = users.map(renderUserRow).join('');
      initFollowButtons();
      }
  }

  function peopleSkeleton() {
    const row =
      '<div class="skeleton-row"><div class="skeleton skeleton--avatar"></div>' +
      '<div class="skeleton-row__lines">' +
      '<div class="skeleton skeleton--line" style="width:45%"></div>' +
      '<div class="skeleton skeleton--line" style="width:30%"></div></div>' +
      '<div class="skeleton skeleton--pill"></div></div>';
    return row + row + row;
  }

  let avatarFallbackBound = false;
  function initAvatarFallback() {
    if (avatarFallbackBound) return;
    avatarFallbackBound = true;
    document.addEventListener('error', function (e) {
      const img = e.target;
      if (!img || img.tagName !== 'IMG') return;
      if (img.dataset.fallbackApplied) return;
      if (img.getAttribute('src') === DEFAULT_AVATAR) return;
      img.dataset.fallbackApplied = '1';
      img.src = DEFAULT_AVATAR;
    }, true);
  }

  function initProfileViewToggle() {
    const buttons = Array.from(document.querySelectorAll('[data-profile-view]'));
    const gridWrap = document.getElementById('profileGridWrap');
    const grid = document.getElementById('profileGrid');
    const list = document.getElementById('profilePosts');
    const repostsWrap = document.getElementById('profileRepostsWrap');
    const repostsLoader = repostsWrap ? repostsWrap.querySelector('[data-profile-reposts-loader]') : null;
    const timelineLoader = list ? list.querySelector('[data-load-posts]') : null;
    if (!buttons.length || !list) return;

    function apply(view) {
      buttons.forEach(b => b.classList.toggle('is-active', b.dataset.profileView === view));
      if (gridWrap) gridWrap.hidden = view !== 'grid';
      if (repostsWrap) repostsWrap.hidden = view !== 'reposts';
      list.hidden = view !== 'list';
      if (timelineLoader && gridWrap) {
        const end = timelineLoader.nextElementSibling;
        const parent = view === 'grid' ? gridWrap : list;
        parent.appendChild(timelineLoader);
        if (end?.matches('[data-posts-end]')) parent.appendChild(end);
        timelineLoader.dispatchEvent(new Event('aloute:loader-visible'));
      }
      if (view === 'reposts' && repostsLoader && repostsLoader.dataset.profileInitial === '1') {
        repostsLoader.dataset.profileInitial = '0';
        setTimeout(() => repostsLoader.click(), 0);
      }
      const hash = view === 'grid' ? '#luoi' : (view === 'reposts' ? '#dang-lai' : '');
      try { history.replaceState(null, '', hash || location.pathname); } catch (e) {}
    }

    buttons.forEach(b => b.addEventListener('click', () => apply(b.dataset.profileView)));

    let initialView = 'list';
    if (location.hash === '#luoi' && gridWrap) initialView = 'grid';
    else if (location.hash === '#dang-lai' && repostsWrap) initialView = 'reposts';
    apply(initialView);

    if (grid) {
      grid.addEventListener('click', (e) => {
        const tile = e.target.closest('[data-goto-post]');
        if (!tile) return;
        apply('list');
        const target = list.querySelector('.post[data-post-id="' + tile.dataset.gotoPost + '"]');
        if (!target) return;
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('post--highlight');
        setTimeout(() => target.classList.remove('post--highlight'), 1600);
      });
    }
  }

  let activeProfileActionMenu = null;
  let profileActionMenuGlobalListenersBound = false;

  function initProfileActionMenu() {
    const menu = document.querySelector('[data-profile-action-menu]');
    activeProfileActionMenu = menu || null;
    if (!profileActionMenuGlobalListenersBound) {
      document.addEventListener('click', (event) => {
        const active = activeProfileActionMenu;
        if (!active || active.contains(event.target)) return;
        const list = active.querySelector('.profile-action-menu__list');
        const trigger = active.querySelector('.profile-action-menu__trigger');
        if (list) list.hidden = true;
        if (trigger) trigger.setAttribute('aria-expanded', 'false');
      });
      document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        const active = activeProfileActionMenu;
        if (!active) return;
        const list = active.querySelector('.profile-action-menu__list');
        const trigger = active.querySelector('.profile-action-menu__trigger');
        if (list) list.hidden = true;
        if (trigger) trigger.setAttribute('aria-expanded', 'false');
      });
      profileActionMenuGlobalListenersBound = true;
    }
    if (!menu || menu.dataset.bound === '1') return;
    menu.dataset.bound = '1';
    const trigger = menu.querySelector('.profile-action-menu__trigger');
    const list = menu.querySelector('.profile-action-menu__list');
    const userId = menu.dataset.userId;
    if (!trigger || !list || !userId) return;

    const closeMenu = () => {
      list.hidden = true;
      trigger.setAttribute('aria-expanded', 'false');
    };
    trigger.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      const shouldOpen = list.hidden;
      list.hidden = !shouldOpen;
      trigger.setAttribute('aria-expanded', String(shouldOpen));
    });

    const blockButton = menu.querySelector('[data-profile-block]');
    if (blockButton) blockButton.addEventListener('click', async () => {
      closeMenu();
      const confirmed = await AlouteUI.confirm({
        title: 'Chặn tài khoản',
        message: 'Bạn sẽ không còn thấy nội dung hoặc tương tác từ tài khoản này. Bạn muốn tiếp tục?',
        okText: 'Chặn'
      });
      if (!confirmed) return;
      blockButton.disabled = true;
      try {
        await api('/api/blocks/' + encodeURIComponent(userId), { method: 'PUT' });
        queueFlashToast('Đã chặn tài khoản', false);
        window.location.href = '/feed';
      } catch (error) {
        toast(error.message || 'Không thể chặn tài khoản', true);
        blockButton.disabled = false;
      }
    });

    const reportButton = menu.querySelector('[data-profile-report]');
    if (reportButton) reportButton.addEventListener('click', async () => {
      closeMenu();
      const reason = await AlouteUI.prompt({
        title: 'Báo cáo tài khoản',
        label: 'Cho biết lý do bạn báo cáo tài khoản này',
        placeholder: 'Ví dụ: giả mạo, quấy rối, nội dung không phù hợp...',
        okText: 'Gửi báo cáo',
        required: true
      });
      if (!reason) return;
      reportButton.disabled = true;
      try {
        await api('/api/reports', {
          method: 'POST',
          body: { targetType: 'USER', targetId: String(userId), reason: reason }
        });
        toast('Đã gửi báo cáo. Cảm ơn bạn đã giúp cộng đồng an toàn hơn.');
      } catch (error) {
        toast(error.message || 'Không thể gửi báo cáo', true);
      } finally {
        reportButton.disabled = false;
      }
    });
  }

  async function notificationTarget(item) {
    const type = item.dataset.notificationType || '';
    const referenceId = (item.dataset.referenceId || '').trim();
    if (!referenceId) return null;
    if (['FOLLOW', 'FOLLOW_REQUEST_ACCEPTED', 'CLOSE_FRIEND_REQUEST', 'CLOSE_FRIEND_ACCEPTED'].includes(type)) {
      return /^\d+$/.test(referenceId) ? '/profile/' + encodeURIComponent(referenceId) : null;
    }
    if (type === 'CONTENT_REPORT_OPENED') {
      return '/admin/moderation?reportId=' + encodeURIComponent(referenceId);
    }
    if (['COMMUNITY_POST_REPORTED', 'COMMUNITY_REVIEW', 'COMMUNITY_POST_OK', 'COMMUNITY_POST_NO'].includes(type)) {
      try {
        const locationInfo = await api('/api/communities/posts/' + encodeURIComponent(referenceId) + '/location');
        return locationInfo && typeof locationInfo.url === 'string' ? locationInfo.url : '/communities';
      } catch (_) {
        return '/communities';
      }
    }
    if (['LIKE', 'COMMENT', 'REPOST', 'MENTION', 'POST_MODERATION_FLAGGED', 'POST_MODERATION_HIDDEN'].includes(type)) {
      return '/feed?postId=' + encodeURIComponent(referenceId);
    }
    return null;
  }

  async function markNotificationRead(item) {
    if (item.dataset.markingRead === '1') return false;
    if (!item.classList.contains('notif-item--unread')) return true;
    item.dataset.markingRead = '1';
    try {
      await api('/api/notifications/' + encodeURIComponent(item.dataset.notificationId) + '/read', { method: 'POST' });
      item.classList.remove('notif-item--unread');
      const badge = document.getElementById('notifUnreadBadge');
      const current = navBadgeCounts.get('notifUnreadBadge') ?? (badge ? Number.parseInt(badge.textContent, 10) || 0 : 0);
      setBadge('notifUnreadBadge', Math.max(0, current - 1));
      refreshNavBadges(true);
      return true;
    } catch (error) {
      toast(error.message || 'Không thể cập nhật trạng thái thông báo', true);
      return false;
    } finally {
      delete item.dataset.markingRead;
    }
  }

  function bindActivityNotificationItem(item) {
    if (!item || item.dataset.notificationBound === '1') return;
    item.dataset.notificationBound = '1';
    const open = async () => {
      const marked = await markNotificationRead(item);
      if (marked === false) return;
      const target = await notificationTarget(item);
      if (target) window.location.href = target;
    };
    item.addEventListener('click', event => {
      if (event.target.closest('button, a, input, textarea, select')) return;
      open();
    });
    item.addEventListener('keydown', event => {
      if (event.target !== item || !['Enter', ' '].includes(event.key)) return;
      event.preventDefault();
      open();
    });
  }

  function appendRealtimeActivityNotification(notification) {
    const page = document.querySelector('[data-activity-page="true"]');
    if (!page || !notification || notification.id == null) return;
    const list = page.querySelector('.feed-panel');
    if (!list) return;
    const id = String(notification.id);
    if (list.querySelector('[data-notification-id="' + CSS.escape(id) + '"]')) return;
    list.querySelector('.empty-state')?.remove();

    const item = document.createElement('div');
    item.className = 'notif-item' + (notification.read ? '' : ' notif-item--unread');
    item.dataset.notificationId = id;
    item.dataset.notificationType = String(notification.type || '');
    item.dataset.referenceId = String(notification.referenceId || '');
    item.tabIndex = 0;
    item.setAttribute('role', 'link');
    item.setAttribute('aria-label', 'Mở thông báo');

    const icon = document.createElement('div');
    icon.className = 'notif-item__icon';
    const iconText = notification.type === 'FOLLOW_REQUEST_ACCEPTED' ? '✓'
      : (notification.type === 'POST_MODERATION_HIDDEN' ? '!' : (notification.type === 'POST_MODERATION_FLAGGED' ? '⚑' : '♡'));
    icon.textContent = iconText;

    const body = document.createElement('div');
    body.className = 'notif-item__body';
    const content = document.createElement('div');
    content.textContent = String(notification.content || 'Bạn có thông báo mới');
    const time = document.createElement('div');
    time.className = 'notif-item__time';
    const createdAt = notification.createdAt ? new Date(notification.createdAt) : new Date();
    time.textContent = Number.isNaN(createdAt.getTime()) ? 'Vừa xong'
      : createdAt.toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', year: 'numeric' });
    body.append(content, time);
    item.append(icon, body);
    list.prepend(item);
    bindActivityNotificationItem(item);
  }

  function initActivityPage() {
    const activityPage = document.querySelector('[data-activity-page="true"]');
    if (activityPage && activityPage.dataset.openMarkedRead !== '1') {
      activityPage.dataset.openMarkedRead = '1';
      // Opening the notification page is itself the read action. The server marks existing records
      // first on full/soft navigation; this request also closes the race for realtime arrivals.
      api('/api/notifications/read-all', { method: 'POST' }).then(() => {
        document.querySelectorAll('.notif-item--unread').forEach(el => el.classList.remove('notif-item--unread'));
        setBadge('notifUnreadBadge', 0);
        navBadgeRefreshAt = 0;
        refreshNavBadges(true);
      }).catch(error => {
        toast(error.message || 'Không thể đánh dấu thông báo đã đọc', true);
      });
    }
    document.querySelectorAll('.notif-item[data-notification-id]').forEach(bindActivityNotificationItem);
    if (document.body.dataset.activityRealtimeNotificationsBound !== '1') {
      document.body.dataset.activityRealtimeNotificationsBound = '1';
      document.addEventListener('aloute:notification', event => {
        appendRealtimeActivityNotification(event.detail || {});
      });
    }

    const markAllBtn = document.getElementById('markAllReadBtn');
    if (markAllBtn) {
      markAllBtn.addEventListener('click', async () => {
        try {
          await api('/api/notifications/read-all', { method: 'POST' });
          document.querySelectorAll('.notif-item--unread').forEach(el => el.classList.remove('notif-item--unread'));
          setBadge('notifUnreadBadge', 0);
          refreshNavBadges(true);
          toast('Đã đánh dấu tất cả là đã đọc');
        } catch (err) {
          toast(err.message, true);
        }
      });
    }

  }

  function initProfileEditPage() {
    const form = document.getElementById('profileEditForm');
    if (!form) return;

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const fullName = document.getElementById('editFullName').value.trim();
      const bio = document.getElementById('editBio').value.trim();
      const privateToggle = document.getElementById('editPrivateAccount');
      if (!fullName || fullName.length > 100) {
        toast('Họ tên phải có từ 1 đến 100 ký tự.', true);
        return;
      }
      const payload = { fullName, bio };
      if (privateToggle) payload.privateAccount = privateToggle.checked;
      try {
        const updated = await api('/api/users/me', { method: 'PUT', body: payload });
        toast('Đã lưu thay đổi');
        if (!form.closest('.settings-page')) window.location.href = profileUrl(updated?.username, 'me');
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
          toast('Đã cập nhật ảnh đại diện');
          window.location.reload();
        } catch (err) {
          toast(err.message, true);
        }
      });
    }
  }

  function initChangePasswordForm() {
    const form = document.getElementById('changePasswordForm');
    if (!form || form.dataset.bound === 'true') return;
    form.dataset.bound = 'true';

    const currentInput = document.getElementById('currentPassword');
    const newInput = document.getElementById('newPassword');
    const confirmInput = document.getElementById('confirmNewPassword');
    const errorBox = document.getElementById('changePasswordError');
    const successBox = document.getElementById('changePasswordSuccess');
    const submitButton = document.getElementById('changePasswordSubmit') || form.querySelector('[type="submit"]');
    if (!currentInput || !newInput || !confirmInput || !errorBox || !successBox) return;

    const showError = (message, field) => {
      errorBox.textContent = message;
      errorBox.style.display = 'block';
      successBox.style.display = 'none';
      if (field) field.focus();
    };
    const showSuccess = message => {
      successBox.textContent = message;
      successBox.style.display = 'block';
      errorBox.style.display = 'none';
    };

    form.addEventListener('submit', async event => {
      event.preventDefault();
      errorBox.style.display = 'none';
      successBox.style.display = 'none';

      // Validate the present-password field first. The backend then checks its actual value,
      // followed by the new password policy and confirmation, in exactly that order.
      if (!currentInput.value) {
        showError('Vui lòng nhập mật khẩu hiện tại.', currentInput);
        return;
      }

      if (submitButton) { submitButton.disabled = true; submitButton.setAttribute('aria-busy', 'true'); }
      try {
        await api('/api/users/me/change-password', {
          method: 'POST',
          body: {
            currentPassword: currentInput.value,
            newPassword: newInput.value,
            confirmNewPassword: confirmInput.value
          }
        });
        showSuccess('Đổi mật khẩu thành công. Vì lý do bảo mật, bạn sẽ được chuyển về màn hình đăng nhập.');
        form.reset();
        setTimeout(() => { window.location.assign('/login'); }, 1400);
      } catch (error) {
        const message = error.message || 'Đổi mật khẩu thất bại. Vui lòng thử lại.';
        const lower = message.toLocaleLowerCase('vi');
        const field = lower.includes('mật khẩu hiện tại') ? currentInput
          : lower.includes('mật khẩu mới và phần nhập lại') || lower.includes('xác nhận') ? confirmInput
          : lower.includes('mật khẩu mới') ? newInput : null;
        showError(message, field);
      } finally {
        if (submitButton) { submitButton.disabled = false; submitButton.removeAttribute('aria-busy'); }
      }
    });
  }

  async function withLogoutDeadline(operation, milliseconds) {
    const controller = new AbortController();
    let timeout;
    const expired = new Promise((_, reject) => {
      timeout = setTimeout(() => {
        controller.abort();
        reject(new Error('Yêu cầu đăng xuất quá thời gian chờ. Vui lòng thử lại.'));
      }, milliseconds);
    });
    try { return await Promise.race([operation(controller.signal), expired]); }
    finally { clearTimeout(timeout); }
  }

  function initLogout() {
    document.querySelectorAll('#logoutBtn, [data-logout]').forEach((btn) => {
      if (btn.dataset.logoutBound === '1') return;
      btn.dataset.logoutBound = '1';
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        if (logoutInProgress) return;
        logoutInProgress = true;
        const buttons = document.querySelectorAll('#logoutBtn, [data-logout]');
        buttons.forEach(button => button.setAttribute('aria-busy', 'true'));
        try {
          await withLogoutDeadline(async signal => {
            // An already running rotation must finish before logout reads/revokes its new cookie.
            if (sessionRefreshPromise) await sessionRefreshPromise;
            signal.throwIfAborted();
            let pushFid = null;
            try {
              pushFid = localStorage.getItem('aloute-fcm-fid');
              // Stop background push enrollment while this session is being closed.
              localStorage.setItem('aloute-fcm-disabled', 'true');
            } catch (_) { /* Storage may be blocked. */ }
            if (pushFid) {
              try {
                await withLogoutDeadline(pushSignal => api('/api/push/register', {
                  method: 'DELETE', body: { fid: pushFid }, signal: pushSignal
                }), 2000);
              } catch (_) { /* Push cleanup is best effort and must not hold logout indefinitely. */ }
            }
            signal.throwIfAborted();
            await api('/api/auth/logout', { method: 'POST', signal });
          }, 10000);
          try {
            localStorage.removeItem('aloute-fcm-fid');
            localStorage.setItem('aloute-fcm-disabled', 'true');
          } catch (_) { /* Server cookies have already been cleared even if storage is blocked. */ }
          window.location.replace('/login');
        } catch (error) {
          logoutInProgress = false;
          buttons.forEach(button => button.removeAttribute('aria-busy'));
          toast(error.message || 'Chưa đăng xuất được. Vui lòng thử lại.', true);
        }
      });
    });
  }

  function appendGridTiles(posts) {
    const grid = document.getElementById('profileGrid');
    if (!grid) return;
    posts.forEach(post => {
      // Repost fragments contain the original author's post, not this profile's own media.
      if (!post.closest('#profilePosts') || post.classList.contains('post--repost')) return;
      const img = post.querySelector('.post__image');
      const video = post.querySelector('.post__video');
      if (!img && !video) return;
      const likeCount = post.querySelector('.action--like span');
      const replyCount = post.querySelector('.action--reply span');
      const tile = document.createElement('button');
      tile.type = 'button';
      tile.className = 'grid-tile';
      tile.dataset.gotoPost = post.dataset.postId || '';
      tile.innerHTML =
        (img ? '<img class="grid-tile__image" loading="lazy" decoding="async" alt="" src="' + escapeHtml(img.getAttribute('src')) + '">'
          : '<video class="grid-tile__image" muted preload="metadata" src="' + escapeHtml(video.getAttribute('src')) + '"></video>') +
        '<span class="grid-tile__overlay">' +
          '<span class="grid-tile__stat"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 20.2s-7.2-4.4-9-9C1.7 7.6 3.2 5 6 4.6c1.9-.3 3.4.6 4.3 1.9.6-.9 1.5-1.6 2.5-1.9 2.9-.6 4.9 2 4.5 4.6-.7 4.7-9.3 10.5-9.3 10.5Z"/></svg><span>' +
            escapeHtml(likeCount ? likeCount.textContent : '0') + '</span></span>' +
          '<span class="grid-tile__stat"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 11.5a8.4 8.4 0 0 1-8.6 8.4c-1.3 0-2.6-.3-3.7-.9L3 20l1.1-5.4a8.3 8.3 0 0 1-.7-3.1A8.4 8.4 0 0 1 12.1 3a8.4 8.4 0 0 1 8.9 8.5Z"/></svg><span>' +
            escapeHtml(replyCount ? replyCount.textContent : '0') + '</span></span>' +
        '</span>';
      grid.appendChild(tile);
    });
    const empty = document.querySelector('[data-profile-media-empty]');
    if (empty) empty.hidden = grid.children.length > 0;
  }

  const pageDisposers = new Set();

  function watchLoadMore(button, load) {
    let disposed = false;
    let frame = 0;
    // Start while roughly two screens remain, giving the request time to finish before
    // the reader reaches the last post. Keep the same one-request-at-a-time guard.
    const preloadDistance = Math.max(1200, Math.min(2400, Math.round(window.innerHeight * 2)));
    const observer = 'IntersectionObserver' in window ? new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting) && !disposed && button.isConnected
          && !button.disabled && !button.hidden && !button.closest('[hidden]')) load();
    }, { rootMargin: '0px 0px ' + preloadDistance + 'px 0px' }) : null;
    function refresh() {
      if (disposed) return;
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        if (disposed || !button.isConnected) return;
        // Re-observe even if a short/filtered page leaves the loader in the viewport.
        observer?.unobserve(button);
        if (!button.hidden) observer?.observe(button);
      });
    }
    function dispose() {
      disposed = true;
      cancelAnimationFrame(frame);
      observer?.disconnect();
      button.removeEventListener('aloute:loader-visible', refresh);
      pageDisposers.delete(dispose);
    }
    function pause() {
      cancelAnimationFrame(frame);
      observer?.disconnect();
    }
    button.addEventListener('aloute:loader-visible', refresh);
    pageDisposers.add(dispose);
    refresh();
    return { refresh, pause, dispose };
  }

  function initPostPagination() {
    document.querySelectorAll('[data-load-posts]').forEach(button => {
      if (button.dataset.cursorBound === '1') return;
      button.dataset.cursorBound = '1';
      const container = button.parentElement;
      const isTimeline = container.id === 'profilePosts';
      const end = document.createElement('p');
      end.className = 'post-pagination__status';
      end.dataset.postsEnd = '';
      end.setAttribute('role', 'status');
      end.textContent = 'Bạn đã xem hết bài viết.';
      end.hidden = true;
      button.after(end);
      const label = button.textContent;
      const controller = new AbortController();
      pageDisposers.add(() => controller.abort());
      let loading = false;
      // Community fragments still use numbered pages rather than cursor headers.
      const numbered = button.hasAttribute('data-next-page');
      const watcher = numbered ? null : watchLoadMore(button, load);
      button.addEventListener('click', load);

      async function load() {
        if (loading || !button.isConnected || button.dataset.exhausted === '1') return;
        loading = true;
        button.disabled = true;
        button.textContent = 'Đang tải bài viết…';
        button.setAttribute('aria-busy', 'true');
        let succeeded = false;
        try {
          const url = new URL(button.dataset.url, window.location.origin);
          const previousCursor = button.dataset.cursor || '';
          if (numbered) url.searchParams.set('page', button.dataset.nextPage);
          else if (previousCursor) url.searchParams.set('cursor', previousCursor);
          else url.searchParams.delete('cursor');
          const response = await fetchWithSessionRefresh(url, { credentials: 'same-origin', signal: controller.signal });
          if (!response.ok || response.redirected) throw new Error('Không tải được bài viết');
          const fragment = document.createElement('template');
          fragment.innerHTML = await response.text();
          if (!button.isConnected || controller.signal.aborted) return;
          const candidates = Array.from(fragment.content.querySelectorAll('.post'));
          const nextCursor = response.headers.get('X-Next-Cursor') || '';
          const hasMore = numbered ? candidates.length === 20 : response.headers.get('X-Has-More') === 'true';
          if (hasMore && !numbered && (!nextCursor || nextCursor === previousCursor)) {
            throw new Error('Chưa tải được các bài tiếp theo. Vui lòng thử lại.');
          }
          const seen = new Set(Array.from(container.querySelectorAll('.post'), post => post.dataset.postId));
          const posts = candidates.filter(post => {
            if (post.dataset.postId && seen.has(post.dataset.postId)) return false;
            if (post.dataset.postId) seen.add(post.dataset.postId);
            return true;
          });
          posts.forEach(post => container.insertBefore(post, button.parentElement === container ? button : null));
          if (button.hasAttribute('data-profile-reposts-loader')) {
            const emptyState = document.querySelector('[data-profile-reposts-empty]');
            const hasAnyReposts = Boolean(button.parentElement && button.parentElement.querySelector('.post'));
            if (emptyState) emptyState.hidden = hasAnyReposts || response.headers.get('X-Has-More') === 'true';
          }
          if (isTimeline) appendGridTiles(posts);
          button.dataset.cursor = nextCursor;
          if (numbered) button.dataset.nextPage = String(Number(button.dataset.nextPage) + 1);
          button.hidden = !hasMore;
          if (!hasMore) {
            button.dataset.exhausted = '1';
            end.hidden = false;
            watcher?.dispose();
          }
          initFeedInteractions();
          formatInitialCounts();
          succeeded = true;
        } catch (err) {
          if (!controller.signal.aborted && button.isConnected) {
            button.textContent = 'Thử tải lại';
            watcher?.pause();
            toast(err.message, true);
          }
        } finally {
          loading = false;
          button.disabled = false;
          button.removeAttribute('aria-busy');
          if (succeeded) {
            button.textContent = label;
            watcher?.refresh();
          }
        }
      }
    });
  }

  function setCommunityJoinButton(btn, joined) {
    btn.classList.toggle('is-joined', joined);
    btn.classList.toggle('btn-outline', joined);
    btn.classList.toggle('btn-primary', !joined);
    btn.textContent = joined ? 'Đã tham gia' : 'Tham gia';
  }

  function initCommunityPages() {
    const page = document.querySelector('.community-page');
    if (!page) return;

    const communitySearchInput = document.getElementById('communitySearchInput');
    const communitySearchClear = document.getElementById('communitySearchClear');
    const communitySearchResults = document.getElementById('communitySearchResults');
    const communitySearchList = document.getElementById('communitySearchList');
    const communitySearchCount = document.getElementById('communitySearchCount');
    const communitySearchState = document.getElementById('communitySearchState');
    const communityBrowseSections = document.getElementById('communityBrowseSections');
    let communitySearchTimer = null;
    let communitySearchVersion = 0;

    const renderCommunitySearchResult = (c) => {
      const card = document.createElement('div');
      card.className = 'community-card';

      const avatarLink = document.createElement('a');
      avatarLink.className = 'community-card__avatar';
      avatarLink.href = '/communities/' + encodeURIComponent(c.id);
      const avatar = document.createElement('img');
      avatar.alt = '';
      avatar.src = c.avatarUrl || '/images/avatar_default.png';
      avatarLink.appendChild(avatar);

      const body = document.createElement('a');
      body.className = 'community-card__body';
      body.href = '/communities/' + encodeURIComponent(c.id);
      const name = document.createElement('strong');
      name.textContent = c.name || 'Hội nhóm';
      const meta = document.createElement('span');
      meta.className = 'community-card__meta';
      meta.textContent = (c.memberCount || 0) + ' thành viên · ' + (c.privacy === 'PRIVATE' ? 'Riêng tư' : 'Công khai');
      body.appendChild(name);
      body.appendChild(meta);
      if (c.description) {
        const desc = document.createElement('span');
        desc.className = 'community-card__desc';
        desc.textContent = c.description;
        body.appendChild(desc);
      }

      const role = String(c.role || '').toUpperCase();
      const pending = role === 'PENDING';
      const banned = role === 'BANNED';
      const action = document.createElement('button');
      action.type = 'button';
      action.className = 'community-card__action ' + (c.member ? 'btn-outline is-joined' : 'btn-primary') + ' btn-community-join';
      action.dataset.communityId = c.id;
      action.textContent = pending ? 'Đã gửi yêu cầu' : (banned ? 'Đã bị cấm' : (c.member ? 'Đã tham gia' : 'Tham gia'));
      action.disabled = pending || banned;
      if (pending || banned) action.classList.add('btn-outline');

      card.appendChild(avatarLink);
      card.appendChild(body);
      card.appendChild(action);
      action.addEventListener('click', async () => {
        if (action.disabled || action.dataset.busy === '1') return;
        const joined = action.classList.contains('is-joined');
        if (joined && !(await AlouteUI.confirm({ title: 'Rời hội nhóm', message: 'Bạn muốn rời khỏi hội nhóm này?', okText: 'Rời nhóm' }))) return;
        action.dataset.busy = '1';
        setCommunityJoinButton(action, !joined);
        try {
          const membership = await api('/api/communities/' + encodeURIComponent(c.id) + (joined ? '/leave' : '/join'), { method: 'POST' });
          if (!joined && membership && String(membership.role || '').toUpperCase() === 'PENDING') {
            action.classList.remove('btn-primary', 'is-joined');
            action.classList.add('btn-outline');
            action.textContent = 'Đã gửi yêu cầu';
            action.disabled = true;
          }
        } catch (err) {
          setCommunityJoinButton(action, joined);
          toast(err.message || 'Không thể cập nhật hội nhóm', true);
        } finally {
          delete action.dataset.busy;
        }
      });
      return card;
    };

    const runCommunitySearch = async () => {
      if (!communitySearchInput || !communitySearchResults || !communitySearchList) return;
      const keyword = communitySearchInput.value.trim();
      if (!keyword) {
        communitySearchResults.hidden = true;
        if (communityBrowseSections) communityBrowseSections.hidden = false;
        if (communitySearchClear) communitySearchClear.hidden = true;
        return;
      }

      if (communitySearchClear) communitySearchClear.hidden = false;
      communitySearchResults.hidden = false;
      if (communityBrowseSections) communityBrowseSections.hidden = true;
      communitySearchList.innerHTML = '';
      if (communitySearchState) {
        communitySearchState.hidden = false;
        communitySearchState.textContent = 'Đang tìm kiếm...';
      }
      const version = ++communitySearchVersion;
      try {
        const result = await api('/api/communities/search?q=' + encodeURIComponent(keyword) + '&limit=30');
        if (version !== communitySearchVersion) return;
        const list = Array.isArray(result) ? result : [];
        if (communitySearchCount) communitySearchCount.textContent = list.length + ' kết quả';
        list.forEach(c => communitySearchList.appendChild(renderCommunitySearchResult(c)));
        if (communitySearchState) {
          communitySearchState.hidden = list.length > 0;
          if (!list.length) communitySearchState.textContent = 'Không tìm thấy hội nhóm phù hợp.';
        }
      } catch (err) {
        if (version !== communitySearchVersion) return;
        if (communitySearchCount) communitySearchCount.textContent = '';
        if (communitySearchState) {
          communitySearchState.hidden = false;
          communitySearchState.textContent = err.message || 'Không tìm được hội nhóm.';
        }
      }
    };

    if (communitySearchInput && communitySearchInput.dataset.bound !== '1') {
      communitySearchInput.dataset.bound = '1';
      communitySearchInput.addEventListener('input', () => {
        clearTimeout(communitySearchTimer);
        communitySearchTimer = setTimeout(runCommunitySearch, 220);
      });
    }
    if (communitySearchClear && communitySearchClear.dataset.bound !== '1') {
      communitySearchClear.dataset.bound = '1';
      communitySearchClear.addEventListener('click', () => {
        communitySearchInput.value = '';
        runCommunitySearch();
        communitySearchInput.focus();
      });
    }

    const openBtn = document.getElementById('openCreateCommunityBtn');
    const modal = document.getElementById('createCommunityModal');
    if (openBtn && modal) {
      const closeBtn = document.getElementById('closeCreateCommunityBtn');
      const form = document.getElementById('createCommunityForm');
      const errorEl = document.getElementById('createCommunityError');
      const submitBtn = document.getElementById('createCommunitySubmitBtn');
      const nameInput = document.getElementById('newCommunityName');
      const descInput = document.getElementById('newCommunityDescription');

      const coverInput = document.getElementById('newCommunityCoverInput');
      const coverPreview = document.getElementById('newCommunityCoverPreview');
      const avatarInput = document.getElementById('newCommunityAvatarInput');
      const avatarImg = document.getElementById('newCommunityAvatarImg');
      const avatarIcon = document.getElementById('newCommunityAvatarIcon');

      function previewFile(file, onLoad) {
        if (!file) return;
        const reader = new FileReader();
        reader.onload = () => onLoad(reader.result);
        reader.readAsDataURL(file);
      }

      if (coverInput && coverPreview) {
        coverInput.addEventListener('change', () => {
          const file = coverInput.files[0];
          if (!file) return;
          previewFile(file, (dataUrl) => {
            coverPreview.style.backgroundImage = 'url(' + dataUrl + ')';
            coverPreview.classList.add('has-image');
          });
        });
      }
      if (avatarInput && avatarImg) {
        avatarInput.addEventListener('change', () => {
          const file = avatarInput.files[0];
          if (!file) return;
          previewFile(file, (dataUrl) => {
            avatarImg.src = dataUrl;
            avatarImg.hidden = false;
            if (avatarIcon) avatarIcon.hidden = true;
          });
        });
      }

      const openModal = () => { modal.hidden = false; if (nameInput) nameInput.focus(); };
      const closeModal = () => { modal.hidden = true; if (errorEl) errorEl.style.display = 'none'; };

      openBtn.addEventListener('click', openModal);
      if (closeBtn) closeBtn.addEventListener('click', closeModal);
      modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });
      if (page.dataset.communityEscapeBound !== '1') {
        document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !modal.hidden) closeModal(); });
        page.dataset.communityEscapeBound = '1';
      }

      async function uploadIfSelected(input) {
        const file = input && input.files[0];
        if (!file) return null;
        const fd = new FormData();
        fd.append('file', file);
        fd.append('purpose', 'community');
        const res = await api('/api/upload/image', { method: 'POST', body: fd });
        return res.url;
      }

      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = nameInput.value.trim();
        if (!name) return;
        const checked = form.querySelector('input[name="newCommunityPrivacy"]:checked');
        submitBtn.disabled = true;
        const originalLabel = submitBtn.textContent;
        if (errorEl) errorEl.style.display = 'none';
        try {
          submitBtn.textContent = 'Đang tạo...';
          const [avatarUrl, coverUrl] = await Promise.all([
            uploadIfSelected(avatarInput),
            uploadIfSelected(coverInput)
          ]);
          const created = await api('/api/communities', {
            method: 'POST',
            body: {
              name: name,
              description: descInput.value.trim(),
              privacy: checked ? checked.value : 'PUBLIC',
              avatarUrl: avatarUrl,
              coverUrl: coverUrl
            }
          });
          location.href = '/communities/' + created.id;
        } catch (err) {
          if (errorEl) {
            errorEl.textContent = err.message || 'Không thể tạo hội nhóm';
            errorEl.style.display = 'block';
          }
          submitBtn.disabled = false;
          submitBtn.textContent = originalLabel;
        }
      });
    }

    document.querySelectorAll('.btn-community-join').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (btn.dataset.busy === '1') return;
        const id = btn.dataset.communityId;
        const wasJoined = btn.classList.contains('is-joined');
        if (wasJoined && !(await AlouteUI.confirm({ title: 'Rời hội nhóm', message: 'Bạn muốn rời khỏi hội nhóm này?', okText: 'Rời nhóm' }))) return;

        btn.dataset.busy = '1';
        setCommunityJoinButton(btn, !wasJoined);
        try {
          const membership = await api('/api/communities/' + id + (wasJoined ? '/leave' : '/join'), { method: 'POST' });
          if (page.isConnected && page.classList.contains('community-page--list') && (wasJoined || membership?.member)) {
            // Refresh both lists from the server after membership changes.
            await navigateSoft(new URL(window.location.href), false);
          }
        } catch (err) {
          setCommunityJoinButton(btn, wasJoined);
          toast(err.message || 'Không thể thực hiện thao tác này', true);
        } finally {
          btn.dataset.busy = '0';
        }
      });
    });

    const communityId = page.dataset.communityId;
    if (!communityId) return;
    const isCommunityManager = page.dataset.communityManager === 'true';
    const isCommunityOwner = page.dataset.communityOwner === 'true';

    function communityState(text) {
      const p = document.createElement('p');
      p.className = 'community-management__state';
      p.textContent = text;
      return p;
    }

    function communityAction(label, className, handler) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = className || 'btn-outline';
      button.textContent = label;
      button.addEventListener('click', async () => {
        if (button.dataset.busy === '1') return;
        button.dataset.busy = '1';
        button.disabled = true;
        try { await handler(); }
        catch (err) { toast(err.message || 'Không thể thực hiện thao tác', true); }
        finally { button.dataset.busy = '0'; button.disabled = false; }
      });
      return button;
    }

    async function loadCommunityLeadership() {
      const host = document.getElementById('communityLeadershipList');
      if (!host) return;
      try {
        const leaders = await api('/api/communities/' + communityId + '/leadership') || [];
        host.replaceChildren();
        if (!leaders.length) {
          host.appendChild(communityState('Chưa có chủ nhóm/quản lý được chỉ định. Chủ nhóm cần kiểm tra dữ liệu thành viên của nhóm.'));
          return;
        }
        const labels = { OWNER: 'Chủ nhóm', MANAGER: 'Quản lý' };
        leaders.forEach(leader => {
          const row = document.createElement('div');
          row.className = 'community-leadership__row';
          const avatar = document.createElement('img');
          avatar.src = leader.avatarUrl || '/images/avatar_default.png';
          avatar.alt = '';
          const info = document.createElement('div');
          info.className = 'community-management__info';
          const name = document.createElement('strong');
          name.textContent = leader.fullName || leader.username || ('Người dùng ' + leader.userId);
          const meta = document.createElement('span');
          meta.textContent = '@' + (leader.username || leader.userId) + ' · ' + (labels[leader.role] || leader.role);
          info.append(name, meta);
          row.append(avatar, info);
          host.appendChild(row);
        });
      } catch (err) {
        host.replaceChildren(communityState(err.message || 'Không tải được danh sách quản lý.'));
      }
    }
    if (document.getElementById('communityLeadershipList')) loadCommunityLeadership();

    if (isCommunityOwner) {
      const settingsForm = document.getElementById('communitySettingsForm');
      if (settingsForm) {
        settingsForm.addEventListener('submit', async (event) => {
          event.preventDefault();
          const submit = document.getElementById('saveCommunitySettingsBtn');
          if (submit?.disabled) return;
          if (submit) submit.disabled = true;
          const form = new FormData(settingsForm);
          try {
            await api('/api/communities/' + communityId, {
              method: 'PUT',
              body: {
                name: String(form.get('name') || '').trim(),
                description: String(form.get('description') || '').trim(),
                privacy: String(form.get('privacy') || 'PUBLIC')
              }
            });
            toast('Đã lưu cài đặt hội nhóm');
            location.reload();
          } catch (err) {
            toast(err.message || 'Không thể lưu cài đặt hội nhóm', true);
          } finally { if (submit) submit.disabled = false; }
        });
      }
      const deleteButton = document.getElementById('deleteCommunityBtn');
      if (deleteButton) {
        deleteButton.addEventListener('click', async () => {
          const ok = await AlouteUI.confirm({
            title: 'Xóa hội nhóm',
            message: 'Bạn chắc chắn muốn xóa hội nhóm này? Thao tác không thể hoàn tác.',
            okText: 'Xóa hội nhóm'
          });
          if (!ok) return;
          deleteButton.disabled = true;
          try {
            await api('/api/communities/' + communityId, { method: 'DELETE' });
            toast('Đã xóa hội nhóm');
            location.href = '/communities';
          } catch (err) {
            toast(err.message || 'Không thể xóa hội nhóm', true);
            deleteButton.disabled = false;
          }
        });
      }
    }

    async function loadCommunityMembers() {
      const host = document.getElementById('communityMembersList');
      if (!host) return;
      try {
        const members = await api('/api/communities/' + communityId + '/members?size=100') || [];
        host.replaceChildren();
        if (!members.length) { host.appendChild(communityState('Chưa có thành viên.')); return; }
        members.forEach(member => {
          const row = document.createElement('div');
          row.className = 'community-management__row';
          const avatar = document.createElement('img');
          avatar.src = member.avatarUrl || '/images/avatar_default.png';
          avatar.alt = '';
          const info = document.createElement('div');
          info.className = 'community-management__info';
          const name = document.createElement('strong');
          name.textContent = member.fullName || member.username || ('Người dùng ' + member.userId);
          const meta = document.createElement('span');
          const roleLabels = { OWNER: 'Chủ nhóm', MANAGER: 'Quản lý', MEMBER: 'Thành viên', BANNED: 'Đã bị cấm' };
          meta.textContent = '@' + (member.username || member.userId) + ' · ' + (roleLabels[member.role] || member.role);
          info.append(name, meta);
          const actions = document.createElement('div');
          actions.className = 'community-management__actions';
          if (isCommunityOwner && member.role === 'MEMBER') {
            actions.appendChild(communityAction('Bổ nhiệm', 'btn-outline', async () => {
              await api('/api/communities/' + communityId + '/members/' + member.userId + '/manager?manager=true', { method: 'POST' });
              toast('Đã bổ nhiệm quản lý');
              await loadCommunityMembers();
            }));
          } else if (isCommunityOwner && member.role === 'MANAGER') {
            actions.appendChild(communityAction('Gỡ quản lý', 'btn-outline', async () => {
              await api('/api/communities/' + communityId + '/members/' + member.userId + '/manager?manager=false', { method: 'POST' });
              toast('Đã gỡ quyền quản lý');
              await loadCommunityMembers();
            }));
          }
          if (member.role === 'BANNED') {
            actions.appendChild(communityAction('Bỏ cấm', 'btn-outline', async () => {
              await api('/api/communities/' + communityId + '/members/' + member.userId + '/ban?banned=false', { method: 'POST' });
              toast('Đã bỏ cấm thành viên');
              await loadCommunityMembers();
            }));
          } else if (member.role === 'MEMBER' || (isCommunityOwner && member.role === 'MANAGER')) {
            actions.appendChild(communityAction('Cấm', 'btn-danger', async () => {
              const ok = await AlouteUI.confirm({ title: 'Cấm thành viên', message: 'Thành viên này sẽ không thể tham gia cộng đồng.', okText: 'Cấm' });
              if (!ok) return;
              await api('/api/communities/' + communityId + '/members/' + member.userId + '/ban?banned=true', { method: 'POST' });
              toast('Đã cấm thành viên');
              await loadCommunityMembers();
            }));
          }
          row.append(avatar, info, actions);
          host.appendChild(row);
        });
      } catch (err) {
        host.replaceChildren(communityState(err.message || 'Không tải được danh sách thành viên.'));
      }
    }

    async function loadCommunityRequests(page = 0, append = false) {
      const host = document.getElementById('communityRequestsList');
      if (!host) return;
      try {
        const oldMore = host.querySelector('[data-community-requests-more]');
        if (oldMore) oldMore.remove();
        const requests = await api('/api/communities/' + communityId
          + '/requests?page=' + page + '&size=50') || [];
        if (!append) host.replaceChildren();
        if (!requests.length && page === 0) {
          host.appendChild(communityState('Không có yêu cầu đang chờ.'));
          return;
        }
        requests.forEach(member => {
          const row = document.createElement('div');
          row.className = 'community-management__row';
          const avatar = document.createElement('img');
          avatar.src = member.avatarUrl || '/images/avatar_default.png';
          avatar.alt = '';
          const info = document.createElement('div');
          info.className = 'community-management__info';
          const name = document.createElement('strong');
          name.textContent = member.fullName || member.username || ('Người dùng ' + member.userId);
          const meta = document.createElement('span');
          meta.textContent = '@' + (member.username || member.userId);
          info.append(name, meta);
          const actions = document.createElement('div');
          actions.className = 'community-management__actions';
          actions.append(
            communityAction('Duyệt', 'btn-primary', async () => {
              await api('/api/communities/' + communityId + '/requests/' + member.userId + '?accept=true', { method: 'POST' });
              toast('Đã duyệt thành viên');
              await Promise.all([loadCommunityRequests(), loadCommunityMembers()]);
            }),
            communityAction('Từ chối', 'btn-outline', async () => {
              await api('/api/communities/' + communityId + '/requests/' + member.userId + '?accept=false', { method: 'POST' });
              toast('Đã từ chối yêu cầu');
              await loadCommunityRequests();
            })
          );
          row.append(avatar, info, actions);
          host.appendChild(row);
        });
        if (requests.length === 50) {
          const more = communityAction('Xem thêm yêu cầu', 'btn-outline',
            () => loadCommunityRequests(page + 1, true));
          more.dataset.communityRequestsMore = 'true';
          host.appendChild(more);
        }
      } catch (err) {
        host.replaceChildren(communityState(err.message || 'Không tải được yêu cầu tham gia.'));
      }
    }

    async function loadCommunityPendingPosts() {
      const host = document.getElementById('communityPendingPosts');
      if (!host) return;
      try {
        const posts = await api('/api/communities/' + communityId + '/posts/pending?size=50') || [];
        host.replaceChildren();
        if (!posts.length) { host.appendChild(communityState('Không có bài viết đang chờ.')); return; }
        posts.forEach(post => {
          const row = document.createElement('article');
          row.className = 'community-management__post';
          row.id = 'community-post-review-' + String(post.id);
          const author = document.createElement('strong');
          author.textContent = '@' + (post.username || post.userId);
          const content = document.createElement('p');
          content.textContent = post.content || (post.imageUrl ? '[Bài viết có ảnh]' : '[Bài viết không có nội dung chữ]');
          const actions = document.createElement('div');
          actions.className = 'community-management__actions';
          actions.append(
            communityAction('Duyệt bài', 'btn-primary', async () => {
              await api('/api/communities/' + communityId + '/posts/' + encodeURIComponent(post.id) + '/decision?accept=true', { method: 'POST' });
              toast('Đã duyệt bài viết');
              await loadCommunityPendingPosts();
              location.reload();
            }),
            communityAction('Từ chối', 'btn-outline', async () => {
              await api('/api/communities/' + communityId + '/posts/' + encodeURIComponent(post.id) + '/decision?accept=false', { method: 'POST' });
              toast('Đã từ chối bài viết');
              await loadCommunityPendingPosts();
            })
          );
          row.append(author, content, actions);
          host.appendChild(row);
          if (window.location.hash === '#' + row.id) {
            const management = document.getElementById('communityManagement');
            if (management) management.open = true;
            row.classList.add('community-management__post--targeted');
            requestAnimationFrame(() => row.scrollIntoView({ behavior: 'smooth', block: 'center' }));
          }
        });
      } catch (err) {
        host.replaceChildren(communityState(err.message || 'Không tải được hàng chờ duyệt bài.'));
      }
    }

    function bindCommunityModeratorDeleteButtons() {
      if (!isCommunityManager) return;
      page.querySelectorAll('.post[data-post-id]').forEach(post => {
        const menu = post.querySelector('.post__menu-list');
        if (!menu || menu.querySelector('.community-post-delete') || menu.querySelector('.post__delete')) return;
        const postId = post.dataset.postId;
        const button = communityAction('Xóa khỏi cộng đồng', 'post__menu-item post__menu-item--danger community-post-delete', async () => {
          const ok = await AlouteUI.confirm({ title: 'Xóa bài viết', message: 'Xóa bài viết này khỏi cộng đồng?', okText: 'Xóa' });
          if (!ok) return;
          await api('/api/communities/' + communityId + '/posts/' + encodeURIComponent(postId), { method: 'DELETE' });
          post.remove();
          toast('Đã xóa bài viết khỏi cộng đồng');
        });
        menu.appendChild(button);
      });
    }
    page._bindModeratorDeleteButtons = bindCommunityModeratorDeleteButtons;

    if (isCommunityManager) {
      Promise.all([loadCommunityMembers(), loadCommunityRequests(), loadCommunityPendingPosts()]);
      bindCommunityModeratorDeleteButtons();
    }

    const communityMenu = page.querySelector('[data-community-menu]');
    const communityMenuTrigger = page.querySelector('[data-community-menu-trigger]');
    if (communityMenu && communityMenuTrigger) {
      communityMenuTrigger.addEventListener('click', (event) => {
        event.stopPropagation();
        const isOpen = communityMenu.classList.toggle('is-open');
        communityMenuTrigger.setAttribute('aria-expanded', String(isOpen));
      });
      document.addEventListener('click', (event) => {
        if (!communityMenu.contains(event.target)) {
          communityMenu.classList.remove('is-open');
          communityMenuTrigger.setAttribute('aria-expanded', 'false');
        }
      });
      document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
          communityMenu.classList.remove('is-open');
          communityMenuTrigger.setAttribute('aria-expanded', 'false');
        }
      });
    }

    const reportCommunityBtn = page.querySelector('[data-community-report]');
    if (reportCommunityBtn) {
      reportCommunityBtn.addEventListener('click', async () => {
        communityMenu?.classList.remove('is-open');
        const reason = window.prompt('Vui lòng nhập lý do báo cáo hội nhóm (tối đa 1000 ký tự):');
        if (reason == null) return;
        if (!reason.trim()) { toast('Vui lòng nhập lý do báo cáo', true); return; }
        reportCommunityBtn.disabled = true;
        try {
          await api('/api/reports', {
            method: 'POST',
            body: { targetType: 'COMMUNITY', targetId: String(communityId), reason: reason.trim() }
          });
          toast('Đã gửi báo cáo hội nhóm');
        } catch (err) {
          toast(err.message || 'Không thể gửi báo cáo hội nhóm', true);
        } finally { reportCommunityBtn.disabled = false; }
      });
    }

    const joinBtn = document.getElementById('communityJoinBtn');
    if (joinBtn) {
      joinBtn.addEventListener('click', async () => {
        joinBtn.disabled = true;
        try {
          await api('/api/communities/' + communityId + '/join', { method: 'POST' });
          location.reload();
        } catch (err) {
          toast(err.message || 'Không thể tham gia hội nhóm', true);
          joinBtn.disabled = false;
        }
      });
    }

    const leaveBtn = document.getElementById('communityLeaveBtn');
    if (leaveBtn) {
      leaveBtn.addEventListener('click', async () => {
        if (!(await AlouteUI.confirm({ title: 'Rời hội nhóm', message: 'Bạn muốn rời khỏi hội nhóm này?', okText: 'Rời nhóm' }))) return;
        leaveBtn.disabled = true;
        try {
          await api('/api/communities/' + communityId + '/leave', { method: 'POST' });
          location.href = '/communities';
        } catch (err) {
          toast(err.message || 'Không thể rời hội nhóm', true);
          leaveBtn.disabled = false;
        }
      });
    }


  }

  async function showNewCommunityPost(communityId, postId) {
    const panel = document.getElementById('communityFeedPanel');
    try {
      const res = await fetchWithSessionRefresh('/api/communities/' + communityId + '/posts/fragment?page=0', { credentials: 'same-origin' });
      if (!res.ok || res.redirected) throw new Error('fragment');
      const tpl = document.createElement('template');
      tpl.innerHTML = await res.text();
      const newest = postId
        ? tpl.content.querySelector('.post[data-post-id="' + postId + '"]')
        : tpl.content.querySelector('.post');
      if (!newest || !panel) { toast('Đã đăng bài'); return; }

      panel.querySelectorAll('.empty-state').forEach(el => el.remove());
      const firstPost = panel.querySelector('.post');
      if (firstPost) firstPost.before(newest);
      else panel.prepend(newest);
      initFeedInteractions();
      const communityPage = document.querySelector('.community-page');
      if (communityPage && typeof communityPage._bindModeratorDeleteButtons === 'function') {
        communityPage._bindModeratorDeleteButtons();
      }
      formatInitialCounts();
      toast('Đã đăng bài');
    } catch (e) {
      location.reload();
    }
  }

  function setBadge(elId, count) {
    const el = document.getElementById(elId);
    if (!el) return;
    const n = Math.max(0, Number(count) || 0);
    navBadgeCounts.set(elId, n);
    el.textContent = n > 99 ? '99+' : String(n);
    el.style.display = n > 0 ? '' : 'none';
    document.querySelectorAll('[data-badge-mirror="' + elId + '"]').forEach(function (m) {
      m.textContent = el.textContent;
      m.style.display = el.style.display;
    });
  }

  const navBadgeCounts = new Map();
  let navBadgeUser = null;
  let navBadgeRefreshAt = 0;
  let navBadgeRequest = null;
  let navBadgeForceAfterPending = false;
  let followRequestBadgeRevision = 0;

  function refreshNavBadges(force) {
    const userId = document.body.dataset.currentUserId;
    if (!userId) return;
    if (navBadgeUser !== userId) {
      navBadgeCounts.clear();
      navBadgeRefreshAt = 0;
      navBadgeUser = userId;
    }
    const now = Date.now();
    navBadgeCounts.forEach((count, id) => setBadge(id, count));
    if (navBadgeRequest) {
      if (force) navBadgeForceAfterPending = true;
      return navBadgeRequest;
    }
    if (!force && now - navBadgeRefreshAt < 30000) return;

    const requestRevision = followRequestBadgeRevision;
    navBadgeRequest = Promise.allSettled([
      api('/api/notifications/unread-count', { method: 'GET' }),
      api('/api/chat/unread-count', { method: 'GET' }),
      api('/api/follow-requests/unread-count', { method: 'GET' })
    ]).then(function (results) {
      if (document.body.dataset.currentUserId !== userId) return;
      if (results[0].status === 'fulfilled') setBadge('notifUnreadBadge', results[0].value?.count ?? results[0].value ?? 0);
      if (results[1].status === 'fulfilled') setBadge('chatUnreadBadge', results[1].value?.count ?? results[1].value ?? 0);
      if (results[2].status === 'fulfilled' && requestRevision === followRequestBadgeRevision)
        setBadge('followRequestBadge', results[2].value?.count ?? results[2].value ?? 0);
      navBadgeRefreshAt = Date.now();
    }).finally(function () {
      navBadgeRequest = null;
      if (navBadgeForceAfterPending) {
        navBadgeForceAfterPending = false;
        window.setTimeout(function () { refreshNavBadges(true); }, 0);
      }
    });
  }

  document.addEventListener('aloute:follow-request-updated', function () { refreshNavBadges(true); });

  const SOFT_NAV_ROUTES = ['/feed', '/compose', '/search', '/activity', '/follow-requests', '/saved', '/profile/me', '/people', '/communities', '/settings', '/admin/moderation', '/admin/accounts', '/admin/dashboard', '/host/moderation', '/host/accounts', '/host/server'];
  let restoringHistory = false;
  let softNavigationBusy = false;

  function canSoftNavigate(url) {
    if (url.origin !== window.location.origin) return false;
    if (url.pathname === '/chat' || url.pathname.startsWith('/chat/')) return false;
    if (!SOFT_NAV_ROUTES.some(function (p) { return url.pathname === p || url.pathname.startsWith(p + '/'); })) return false;
    return true;
  }

  function isModifiedClick(e) {
    return e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.defaultPrevented;
  }

  function showPageProgress(show) {
    let bar = document.getElementById('pageNavProgress');
    if (!bar && show) {
      bar = document.createElement('div');
      bar.id = 'pageNavProgress';
      bar.className = 'page-nav-progress';
      document.body.appendChild(bar);
    }
    if (bar) bar.classList.toggle('is-active', !!show);
  }

  async function fetchPageHtml(url) {
    const key = url.href;
    const res = await fetchWithSessionRefresh(key, { credentials: 'same-origin', headers: { 'X-ALOUTE-Navigation': 'soft' }, cache: 'no-store' });
    if (!res.ok || !res.headers.get('content-type')?.includes('text/html')) throw new Error('navigation');
    const html = await res.text();
    return html;
  }

  async function navigateSoft(url, addHistory) {
    if (softNavigationBusy) return;
    softNavigationBusy = true;
    showPageProgress(true);
    try {
      const html = await fetchPageHtml(url);
      const parsed = new DOMParser().parseFromString(html, 'text/html');
      if (!parsed.body || parsed.body.classList.contains('auth-body')) {
        window.location.href = url.href;
        return;
      }
      document.body.className = parsed.body.className;
      document.body.dataset.currentUserId = parsed.body.dataset.currentUserId || '';
      document.body.innerHTML = parsed.body.innerHTML;
      document.title = parsed.title || document.title;
      if (addHistory) {
        window.history.pushState({ alouteSoft: true }, '', url.href);
      }
      runPageInitializers();
      requestAnimationFrame(function () {
        showPageProgress(false);
        window.scrollTo(0, 0);
      });
    } catch (_) {
      window.location.href = url.href;
    } finally {
      softNavigationBusy = false;
      restoringHistory = false;
    }
  }

  function runPageInitializers() {
    for (const dispose of pageDisposers) dispose();
    pageDisposers.clear();
    consumeFlashToast();
    initAvatarFallback();
    initFeedInteractions();
    initPostPagination();
    formatInitialCounts();
    initCommunityPages();
    initComposePage();
    initFollowButtons();
    initCloseFriendButtons();
    initSearchPage();
    initProfileActionMenu();
    initProfilePeoplePanel();
    initProfileViewToggle();
    initActivityPage();
    initProfileEditPage();
    initChangePasswordForm();
    initLogout();
    initSidebarNavigation();
    if (document.querySelector('.follow-requests-page')) {
      followRequestBadgeRevision++;
      const serverCount = parseInt(document.getElementById('followRequestBadge')?.textContent, 10) || 0;
      setBadge('followRequestBadge', serverCount);
      refreshNavBadges(true);
    } else refreshNavBadges();
    document.dispatchEvent(new Event('aloute:navigated'));
  }

  function initSidebarNavigation() {
    const links = document.querySelectorAll('.sidebar a[href], .bottom-nav a[href], .mobile-topbar a[href]');
    links.forEach(function (link) {
      if (link.dataset.softNavBound === '1') return;
      const raw = link.getAttribute('href');
      if (!raw || raw === '#' || link.id === 'logoutBtn' || link.hasAttribute('data-logout')) return;
      let url;
      try { url = new URL(raw, window.location.origin); } catch (_) { return; }
      if (!canSoftNavigate(url)) return;
      link.dataset.softNavBound = '1';
      link.addEventListener('click', function (e) {
        if (isModifiedClick(e)) return;
        if (url.href === window.location.href) return;
        e.preventDefault();
        navigateSoft(url, true);
      });
    });
  }

  window.addEventListener('popstate', function () {
    const url = new URL(window.location.href);
    if (!canSoftNavigate(url)) return;
    restoringHistory = true;
    navigateSoft(url, false);
  });

  document.addEventListener('DOMContentLoaded', function () {

    runPageInitializers();
    const userId = document.body.dataset.currentUserId;
    if (userId) {
      const startRealtime = function () { connectRealtime(); };
      if (window.requestIdleCallback) requestIdleCallback(startRealtime, { timeout: 700 });
      else setTimeout(startRealtime, 180);
    }
  });

  return {
    api, toast, timeAgo, escapeHtml, formatCount, csrfHeaders, fetchWithSessionRefresh, profileUrl,
    onRealtimeReady, connectRealtime, refreshNavBadges,
    initFeedInteractions, initFollowButtons
  };
})();
