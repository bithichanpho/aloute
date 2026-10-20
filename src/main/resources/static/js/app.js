

window.Aloute = (function () {

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

  function refreshSessionOnce() {
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
      throw new Error('Không thể kết nối đến máy chủ. Vui lòng kiểm tra kết nối và thử lại.');
    }

    let payload = null;
    try {
      payload = await res.json();
    } catch (e) {

    }

    if (!res.ok || (payload && payload.success === false)) {
      const message = (payload && payload.message) || ('Máy chủ trả về lỗi ' + res.status);
      throw new Error(message);
    }
    return payload ? payload.data : null;
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
    'https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.6.1/sockjs.min.js',
    'https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js'
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
        toast(notif.message || 'Bạn có thông báo mới');
        document.dispatchEvent(new CustomEvent('aloute:notification', { detail: notif }));
        window.setTimeout(function () { refreshNavBadges(true); }, 180);
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
      link.href = '/profile/' + encodeURIComponent(actor.userId);
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

    trigger.addEventListener('click', function(event) {
      event.stopPropagation();
      wrap.classList.toggle('is-open');
      if (wrap.classList.contains('is-open') && !loaded) loadPage();
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

        function updateRepostBanner(actors, totalCount) {
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
            link.href = '/profile/' + encodeURIComponent(actor.userId);
            link.textContent = actor.username || 'người dùng';
            names.appendChild(link);
          });
          banner.appendChild(names);
          const otherCount = Math.max(0, (Number(totalCount) || actors.length) - actors.length);
          if (otherCount > 0) {
            banner.appendChild(document.createTextNode(' cùng '));

            const wrap = document.createElement('span');
            wrap.className = 'post__repost-others';
            wrap.dataset.repostersPostId = originalPostId;
            wrap.dataset.repostersTotal = String(Number(totalCount) || actors.length);
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
            status.textContent = 'Nhấn để xem danh sách';
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
                updateRepostBanner(result.reposters || [], result.repostCount);
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
        ? '<span class="comment-item__moderation-status" data-moderation-status="FLAGGED">Chờ quản trị viên xem xét</span>'
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
        await api('/api/posts', { method: 'POST', body: { content, imageUrl, videoUrl, visibility: document.getElementById('postVisibility')?.value || 'PUBLIC' } });
        toast('Đã đăng bài viết');
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
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const userId = btn.dataset.userId;
        btn.disabled = true;
        try {
          const following = await api('/api/users/' + userId + '/follow', { method: 'POST' });
          applyFollowState(btn, following);
          if (!following) {
            const status = await api('/api/users/' + userId + '/follow-status');
            if (status === 'PENDING') btn.textContent = 'Đã gửi yêu cầu · Hủy';
          }
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
    btn.textContent = following ? 'Đang theo dõi' : 'Theo dõi';
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

    const cache = { users: {}, posts: {} };
    let currentTab = 'users';
    let keyword = '';
    let debounceTimer = null;
    let requestToken = 0;

    loadDiscoverSections();

    if (bar) {
      input.addEventListener('focus', () => bar.classList.add('is-focused'));
      input.addEventListener('blur', () => bar.classList.remove('is-focused'));
    }

    input.addEventListener('input', () => {
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
          if (keyword) runSearch();
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
      input.value = '';
      keyword = '';
      if (clearBtn) clearBtn.hidden = true;
      showDiscover();
    }

    function showDiscover() {
      requestToken++;
      resultsEl.hidden = true;
      resultsEl.innerHTML = '';
      if (tabsEl) tabsEl.hidden = true;
      if (discoverEl) discoverEl.hidden = false;
    }

    function showResults() {
      if (discoverEl) discoverEl.hidden = true;
      if (tabsEl) tabsEl.hidden = false;
      resultsEl.hidden = false;
    }

    const SEARCH_PAGE_SIZE = 20;

    async function runSearch(page = 0, append = false) {
      showResults();

      const tab = currentTab;
      const key = keyword;
      const token = ++requestToken;

      if (!append && page === 0 && cache[tab][key]) {
        const cached = cache[tab][key];
        paint(tab, cached.items, cached.hasMore, cached.page);
        return;
      }

      if (!append) resultsEl.innerHTML = searchSkeleton(tab);
      try {
        const url = (tab === 'posts' ? '/api/search/posts?q=' : '/api/search/users?q=')
          + encodeURIComponent(key) + '&page=' + page + '&size=' + SEARCH_PAGE_SIZE;
        const data = await api(url);
        if (token !== requestToken) return;
        const batch = data || [];
        const existing = append && cache[tab][key] ? cache[tab][key].items : [];
        cache[tab][key] = {
          items: existing.concat(batch),
          page,
          hasMore: batch.length === SEARCH_PAGE_SIZE
        };
        paint(tab, cache[tab][key].items, cache[tab][key].hasMore, page);
      } catch (err) {
        if (token !== requestToken) return;
        resultsEl.innerHTML = discoverEmpty('Không tải được kết quả', err.message);
      }
    }

    function paint(tab, items, hasMore, page) {
      if (!items.length) {
        resultsEl.innerHTML = tab === 'posts'
          ? discoverEmpty('Chưa có bài viết nào khớp', 'Thử một từ khóa ngắn hơn hoặc kiểm tra lại chính tả.')
          : discoverEmpty('Không tìm thấy ai', 'Bạn có thể tìm theo họ tên hoặc @username.');
        return;
      }

      if (tab === 'posts') {
        resultsEl.innerHTML = '<div class="feed-panel">' + items.map(renderSearchPostCard).join('') + '</div>';
        initFeedInteractions();
        formatInitialCounts();
      } else {
        resultsEl.innerHTML =
          '<div class="discover-card"><div class="discover-card__body">' +
          items.map(renderUserRow).join('') +
          '</div></div>';
        initFollowButtons();
        initCloseFriendButtons();
      }
      if (hasMore) {
        const more = document.createElement('button');
        more.type = 'button';
        more.className = 'btn btn-outline search-load-more';
        more.textContent = 'Xem thêm kết quả';
        more.addEventListener('click', () => runSearch(page + 1, true));
        resultsEl.appendChild(more);
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
      '  <a class="discover-user__main" href="/profile/' + u.id + '">' +
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
      '  <button class="btn-follow' + (u.followedByCurrentUser ? ' is-following' : '') + '" data-user-id="' + u.id + '">' +
      (u.followedByCurrentUser ? 'Đang theo dõi' : 'Theo dõi') +
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

  function renderSearchPostCard(post) {
    const avatar = escapeHtml(post.userAvatarUrl || DEFAULT_AVATAR);
    const when = escapeHtml(post.timeAgo || timeAgo(post.createdAt));
    const actionPostId = escapeHtml(post.interactionPostId || post.id);

    return (
      '<div class="post" data-post-id="' + escapeHtml(post.id) + '" data-action-post-id="' + actionPostId + '">' +
      '  <div class="post__avatar-col">' +
      '    <a href="/profile/' + post.userId + '"><img class="post__avatar" src="' + avatar + '" alt="avatar"></a>' +
      '  </div>' +
      '  <div class="post__body">' +
      '    <div class="post__header">' +
      '      <a href="/profile/' + post.userId + '" class="post__username">' + escapeHtml(post.username || '') + '</a>' +
      '      <span class="post__time">' + when + '</span>' +
      '    </div>' +
      '    <p class="post__content">' + escapeHtml(post.content || '') + '</p>' +
      (post.imageUrl ? '<img class="post__image" src="' + escapeHtml(post.imageUrl) + '" alt="ảnh bài viết">' : '') +
      (post.videoUrl ? '<video class="post__video" src="' + escapeHtml(post.videoUrl) + '" controls preload="metadata"></video>' : '') +
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
    const followBtn = u.followedByCurrentUser
      ? '<button class="btn-follow is-following" data-user-id="' + u.id + '">Đang theo dõi</button>'
      : '<button class="btn-follow" data-user-id="' + u.id + '">Theo dõi</button>';

    let closeFriendBtn = '';
    if (u.mutualFriend && u.closeFriendStatus !== 'PENDING_RECEIVED') {
      const status = u.closeFriendStatus || 'NONE';
      const cls = status === 'ACCEPTED' ? ' is-close' : (status === 'PENDING_SENT' ? ' is-pending' : '');
      const label = status === 'ACCEPTED' ? '★ Bạn thân' : (status === 'PENDING_SENT' ? 'Đã gửi lời mời' : '+ Bạn thân');
      closeFriendBtn = '<button class="btn-close-friend' + cls + '" style="width:auto;margin-top:0;" ' +
        'data-user-id="' + u.id + '" data-status="' + status + '">' + label + '</button>';
    }

    return (
      '<div class="discover-user">' +
      '  <a class="discover-user__main" href="/profile/' + u.id + '">' +
      '    <img class="discover-user__avatar" src="' + escapeHtml(u.avatarUrl || DEFAULT_AVATAR) + '" alt="avatar">' +
      '    <div class="discover-user__info">' +
      '      <div class="discover-user__name">' + escapeHtml(u.fullName || u.username) + '</div>' +
      '      <div class="discover-user__username">@' + escapeHtml(u.username) + '</div>' +
      (u.bio ? '<div class="discover-user__bio">' + escapeHtml(u.bio) + '</div>' : '') +
      '    </div>' +
      '  </a>' +
      '  <div class="discover-user__actions">' + followBtn + closeFriendBtn + '</div>' +
      '</div>'
    );
  }

  const PEOPLE_TABS = {
    followers: { title: 'Người theo dõi', url: id => '/api/users/' + id + '/followers',
                 empty: 'Chưa có ai theo dõi.' },
    following: { title: 'Đang theo dõi', url: id => '/api/users/' + id + '/following',
                 empty: 'Chưa theo dõi ai.' },
    close:     { title: 'Bạn thân', url: id => '/api/close-friends/' + id,
                 empty: 'Chưa có bạn thân nào.' }
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
      initCloseFriendButtons();
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
    if (!buttons.length || !list) return;

    function apply(view) {
      buttons.forEach(b => b.classList.toggle('is-active', b.dataset.profileView === view));
      if (gridWrap) gridWrap.hidden = view !== 'grid';
      if (repostsWrap) repostsWrap.hidden = view !== 'reposts';
      list.hidden = view !== 'list';
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

  function initActivityPage() {
    const markAllBtn = document.getElementById('markAllReadBtn');
    if (markAllBtn) {
      markAllBtn.addEventListener('click', async () => {
        try {
          await api('/api/notifications/read-all', { method: 'POST' });
          document.querySelectorAll('.notif-item--unread').forEach(el => el.classList.remove('notif-item--unread'));
          setBadge('notifUnreadBadge', 0);
          toast('Da danh dau tat ca la da doc');
        } catch (err) {
          toast(err.message, true);
        }
      });
    }

    document.querySelectorAll('.notif-item__actions[data-sender-id]').forEach(actions => {
      const senderId = actions.dataset.senderId;
      const acceptBtn = actions.querySelector('.notif-item__accept');
      const declineBtn = actions.querySelector('.notif-item__decline');

      const disableBoth = () => {
        if (acceptBtn) acceptBtn.disabled = true;
        if (declineBtn) declineBtn.disabled = true;
      };

      const resolveWithLabel = (label) => {
        actions.innerHTML = '<span style="color:var(--text-tertiary); font-size:13px;">' + label + '</span>';
      };

      if (acceptBtn) {
        acceptBtn.addEventListener('click', async () => {
          disableBoth();
          try {
            await api('/api/close-friends/' + senderId + '/accept', { method: 'POST' });
            toast('Đã chấp nhận kết bạn thân');
            resolveWithLabel('Đã chấp nhận');
          } catch (err) {
            toast(err.message, true);
            resolveWithLabel('Lời mời không còn hiệu lực');
          }
        });
      }
      if (declineBtn) {
        declineBtn.addEventListener('click', async () => {
          disableBoth();
          try {
            await api('/api/close-friends/' + senderId, { method: 'DELETE' });
            toast('Đã từ chối lời mời');
            resolveWithLabel('Đã từ chối');
          } catch (err) {
            toast(err.message, true);
            resolveWithLabel('Lời mời không còn hiệu lực');
          }
        });
      }
    });
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
        await api('/api/users/me', { method: 'PUT', body: payload });
        toast('Đã lưu thay đổi');
        if (!form.closest('.settings-page')) window.location.href = '/profile/me';
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
    if (!form) return;

    const errorBox = document.getElementById('changePasswordError');
    const successBox = document.getElementById('changePasswordSuccess');

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      errorBox.style.display = 'none';
      successBox.style.display = 'none';

      const currentPassword = document.getElementById('currentPassword').value;
      const newPassword = document.getElementById('newPassword').value;
      const confirmNewPassword = document.getElementById('confirmNewPassword').value;

      if (newPassword.length < 8 || newPassword.length > 72 || !/[A-Z]/.test(newPassword) || !/[a-z]/.test(newPassword) || !/\d/.test(newPassword) || !/[^\p{L}\p{N}\s]/u.test(newPassword)) {
        errorBox.textContent = 'Mật khẩu phải có 8–72 ký tự, gồm chữ hoa, chữ thường, số và ký tự đặc biệt.';
        errorBox.style.display = 'block';
        return;
      }
      if (newPassword !== confirmNewPassword) {
        errorBox.textContent = 'Hai mat khau moi nhap vao khong khop.';
        errorBox.style.display = 'block';
        return;
      }

      try {
        await api('/api/users/me/change-password', {
          method: 'POST',
          body: { currentPassword, newPassword }
        });
        successBox.textContent = 'Da doi mat khau thanh cong.';
        successBox.style.display = 'block';
        form.reset();
      } catch (err) {
        errorBox.textContent = err.message || 'Doi mat khau that bai.';
        errorBox.style.display = 'block';
      }
    });
  }

  function initLogout() {
    document.querySelectorAll('#logoutBtn, [data-logout]').forEach((btn) => {
      if (btn.dataset.logoutBound === '1') return;
      btn.dataset.logoutBound = '1';
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        // Remove this browser's Firebase Installation ID before ending the ALOUTE session so a shared device
        // does not keep receiving push messages for the account that just signed out.
        let pushFid = null;
        try { pushFid = localStorage.getItem('aloute-fcm-fid'); } catch (storageReadError) { /* Continue logout when storage is blocked. */ }
        if (pushFid) {
          try {
            await api('/api/push/register', { method: 'DELETE', body: JSON.stringify({ fid: pushFid }) });
          } catch (pushCleanupError) {
            // Logout must continue even if FCM installation cleanup is temporarily unavailable.
          }
        }
        try {
          localStorage.removeItem('aloute-fcm-fid');
          localStorage.setItem('aloute-fcm-disabled', 'true');
        } catch (storageError) {
          // Storage may be unavailable in a restrictive browser; still continue to logout.
        }
        try {
          await api('/api/auth/logout', { method: 'POST' });
        } catch (e2) {

        }
        window.location.href = '/login';
      });
    });
  }

  function appendGridTiles(posts) {
    const grid = document.getElementById('profileGrid');
    if (!grid) return;
    posts.forEach(post => {
      const img = post.querySelector('.post__image');
      if (!img) return;
      const likeCount = post.querySelector('.action--like span');
      const replyCount = post.querySelector('.action--reply span');
      const tile = document.createElement('button');
      tile.type = 'button';
      tile.className = 'grid-tile';
      tile.dataset.gotoPost = post.dataset.postId || '';
      tile.innerHTML =
        '<img class="grid-tile__image" loading="lazy" decoding="async" alt="" src="' + escapeHtml(img.getAttribute('src')) + '">' +
        '<span class="grid-tile__overlay">' +
          '<span class="grid-tile__stat"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 20.2s-7.2-4.4-9-9C1.7 7.6 3.2 5 6 4.6c1.9-.3 3.4.6 4.3 1.9.6-.9 1.5-1.6 2.5-1.9 2.9-.6 4.9 2 4.5 4.6-.7 4.7-9.3 10.5-9.3 10.5Z"/></svg><span>' +
            escapeHtml(likeCount ? likeCount.textContent : '0') + '</span></span>' +
          '<span class="grid-tile__stat"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 11.5a8.4 8.4 0 0 1-8.6 8.4c-1.3 0-2.6-.3-3.7-.9L3 20l1.1-5.4a8.3 8.3 0 0 1-.7-3.1A8.4 8.4 0 0 1 12.1 3a8.4 8.4 0 0 1 8.9 8.5Z"/></svg><span>' +
            escapeHtml(replyCount ? replyCount.textContent : '0') + '</span></span>' +
        '</span>';
      grid.appendChild(tile);
    });
  }

  function initPostPagination() {
    document.querySelectorAll('[data-load-posts]').forEach(button => {
      if (button.dataset.cursorBound === '1') return;
      button.dataset.cursorBound = '1';
      button.addEventListener('click', async () => {
        if (button.dataset.exhausted === '1') return;
        button.disabled = true;
        try {
          const url = new URL(button.dataset.url, window.location.origin);
          if (button.dataset.cursor) url.searchParams.set('cursor', button.dataset.cursor);
          else url.searchParams.delete('cursor');
          const response = await fetchWithSessionRefresh(url, { credentials: 'same-origin' });
          if (!response.ok || response.redirected) throw new Error('Không tải được bài viết');
          const fragment = document.createElement('template');
          fragment.innerHTML = await response.text();
          const posts = fragment.content.querySelectorAll('.post');
          const count = posts.length;
          posts.forEach(post => button.before(post));
          if (button.hasAttribute('data-profile-reposts-loader')) {
            const emptyState = document.querySelector('[data-profile-reposts-empty]');
            const hasAnyReposts = Boolean(button.parentElement && button.parentElement.querySelector('.post'));
            if (emptyState) emptyState.hidden = hasAnyReposts || response.headers.get('X-Has-More') === 'true';
          }
          appendGridTiles(posts);
          button.dataset.cursor = response.headers.get('X-Next-Cursor') || '';
          const hasMore = response.headers.get('X-Has-More') === 'true';
          button.hidden = !hasMore;
          if (!hasMore) button.dataset.exhausted = '1';
          initFeedInteractions();
          formatInitialCounts();
          if (!count && hasMore) {
            // The current keyset window may contain only invisible posts; the advanced cursor
            // is still valid, so the next explicit click continues instead of looping on page=0.
            button.hidden = false;
          }
        } catch (err) {
          toast(err.message, true);
        } finally {
          button.disabled = false;
        }
      });
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

      const action = document.createElement('button');
      action.type = 'button';
      action.className = 'community-card__action ' + (c.member ? 'btn-outline is-joined' : 'btn-primary') + ' btn-community-join';
      action.dataset.communityId = c.id;
      action.textContent = c.member ? 'Đã tham gia' : 'Tham gia';

      card.appendChild(avatarLink);
      card.appendChild(body);
      card.appendChild(action);
      action.addEventListener('click', async () => {
        if (action.dataset.busy === '1') return;
        const joined = action.classList.contains('is-joined');
        if (joined && !(await AlouteUI.confirm({ title: 'Rời hội nhóm', message: 'Bạn muốn rời khỏi hội nhóm này?', okText: 'Rời nhóm' }))) return;
        action.dataset.busy = '1';
        setCommunityJoinButton(action, !joined);
        try {
          await api('/api/communities/' + encodeURIComponent(c.id) + (joined ? '/leave' : '/join'), { method: 'POST' });
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
          await api('/api/communities/' + id + (wasJoined ? '/leave' : '/join'), { method: 'POST' });
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

    const composeInput = document.getElementById('communityComposeInput');
    const composeBtn = document.getElementById('communityComposeBtn');
    if (composeInput && composeBtn) {
      composeInput.addEventListener('input', () => {
        composeBtn.disabled = composeInput.value.trim().length === 0;
      });
      composeInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); if (!composeBtn.disabled) composeBtn.click(); }
      });

      composeBtn.addEventListener('click', async () => {
        const content = composeInput.value.trim();
        if (!content) return;
        composeBtn.disabled = true;
        try {
          const created = await api('/api/communities/' + communityId + '/posts', {
            method: 'POST',
            body: { content: content }
          });
          composeInput.value = '';
          if (created && created.communityPostStatus === 'PENDING') {
            toast('Bài viết đã được gửi và đang chờ quản lý duyệt');
            composeBtn.disabled = true;
            return;
          }
          await showNewCommunityPost(communityId, created && created.id);
        } catch (err) {
          toast(err.message || 'Không thể đăng bài', true);
          composeBtn.disabled = false;
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

    navBadgeRequest = Promise.allSettled([
      api('/api/notifications/unread-count', { method: 'GET' }),
      api('/api/chat/unread-count', { method: 'GET' })
    ]).then(function (results) {
      if (document.body.dataset.currentUserId !== userId) return;
      if (results[0].status === 'fulfilled') setBadge('notifUnreadBadge', results[0].value?.count ?? results[0].value ?? 0);
      if (results[1].status === 'fulfilled') setBadge('chatUnreadBadge', results[1].value?.count ?? results[1].value ?? 0);
      navBadgeRefreshAt = Date.now();
    }).finally(function () {
      navBadgeRequest = null;
      if (navBadgeForceAfterPending) {
        navBadgeForceAfterPending = false;
        window.setTimeout(function () { refreshNavBadges(true); }, 0);
      }
    });
  }

  const SOFT_NAV_ROUTES = ['/feed', '/compose', '/search', '/activity', '/saved', '/profile/me', '/communities', '/settings', '/admin/moderation', '/admin/dashboard'];
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
    initAvatarFallback();
    initFeedInteractions();
    initPostPagination();
    formatInitialCounts();
    initCommunityPages();
    initComposePage();
    initFollowButtons();
    initCloseFriendButtons();
    initSearchPage();
    initProfilePeoplePanel();
    initProfileViewToggle();
    initActivityPage();
    initProfileEditPage();
    initChangePasswordForm();
    initLogout();
    initSidebarNavigation();
    refreshNavBadges();
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
    api, toast, timeAgo, escapeHtml, formatCount, csrfHeaders, fetchWithSessionRefresh,
    onRealtimeReady, connectRealtime, refreshNavBadges,
    initFeedInteractions, initFollowButtons, initCloseFriendButtons
  };
})();
