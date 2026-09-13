/* ============================================================
   ALOUTE — chat.js
   Chi chay tren trang /chat. Dung chung ket noi STOMP da duoc
   app.js khoi tao san (Aloute.onRealtimeReady) de gui/nhan tin
   nhan real-time, khong tao them ket noi WebSocket thu 2.

   Tinh nang: gui/nhan tin nhan real-time voi optimistic UI (hien
   bubble ngay, doi chieu lai bang clientId khi server xac nhan),
   bao dang nhap (typing), bao da xem (read receipt), cham online,
   tim kiem/loc hoi thoai, gom nhom bubble theo cum + theo ngay.
   ============================================================ */

(function () {
  const chatList = document.getElementById('chatList');
  const chatListScroll = document.getElementById('chatListScroll');
  const chatThread = document.getElementById('chatThread');
  const emptyState = document.getElementById('chatEmptyState');
  const template = document.getElementById('chatThreadTemplate');
  const searchInput = document.getElementById('chatSearchInput');
  if (!chatList || !chatThread || !template) return;

  const currentUserId = document.body.dataset.currentUserId;

  let activeUserId = null;
  let activeItemEl = null;
  let stompClientRef = null;

  // clientId -> { el, content, receiverId, timeoutId }  (tin nhắn đang chờ server xác nhận)
  const pendingByClientId = new Map();

  // Theo dõi cụm bubble cuối cùng đang hiển thị để gộp nhóm + dời mốc thời gian đúng chỗ
  let lastRenderedSenderId = null;
  let lastRenderedAt = 0;
  let lastRenderedBubbleEl = null;
  let lastRenderedDateKey = null;
  let lastMineBubbleEl = null;

  let typingHideTimer = null;
  let lastTypingSentAt = 0;

  Aloute.onRealtimeReady(function (client) {
    stompClientRef = client;
  });

  // ---------- Tìm kiếm / lọc hội thoại ----------
  if (searchInput) {
    searchInput.addEventListener('input', () => {
      const keyword = searchInput.value.trim().toLowerCase();
      chatList.querySelectorAll('.chat-item[data-user-id]').forEach(item => {
        const name = (item.dataset.fullname || '').toLowerCase();
        const username = (item.dataset.username || '').toLowerCase();
        const match = !keyword || name.includes(keyword) || username.includes(keyword);
        item.style.display = match ? '' : 'none';
      });
    });
  }

  // ---------- Chọn 1 hội thoại ----------
  chatList.querySelectorAll('.chat-item[data-user-id]').forEach(item => {
    item.addEventListener('click', () => openConversation(item));
  });

  async function openConversation(item) {
    chatList.querySelectorAll('.chat-item').forEach(i => i.classList.remove('chat-item--active'));
    item.classList.add('chat-item--active');
    chatList.classList.add('has-active-thread');
    activeItemEl = item;

    const userId = item.dataset.userId;
    const fullName = item.dataset.fullname || item.dataset.username || 'Người dùng';
    const avatar = item.dataset.avatar || 'https://i.pravatar.cc/150?img=32';
    const online = item.dataset.online === 'true';
    activeUserId = userId;

    resetClusterTracking();

    // Xóa badge chưa đọc của hội thoại này ngay khi mở
    const unreadEl = item.querySelector('.chat-item__unread');
    if (unreadEl) unreadEl.remove();

    renderThreadShell(fullName, avatar, online);

    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Đang tải tin nhắn...</span></div>';

    try {
      const messages = await Aloute.api('/api/chat/messages/' + userId);
      renderMessages(messages);
      Aloute.api('/api/chat/read/' + userId, { method: 'POST' }).catch(() => {});
    } catch (err) {
      messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Không tải được tin nhắn: ' + Aloute.escapeHtml(err.message) + '</span></div>';
    }

    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendMessageBtn');

    input.addEventListener('input', () => {
      sendBtn.disabled = input.value.trim().length === 0;
      notifyTyping();
    });
    sendBtn.addEventListener('click', () => sendCurrentMessage());
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); sendCurrentMessage(); }
    });
    input.focus();

    const backBtn = document.getElementById('chatBackBtn');
    if (backBtn) {
      backBtn.addEventListener('click', () => {
        chatList.classList.remove('has-active-thread');
      });
    }
  }

  function resetClusterTracking() {
    lastRenderedSenderId = null;
    lastRenderedAt = 0;
    lastRenderedBubbleEl = null;
    lastRenderedDateKey = null;
    lastMineBubbleEl = null;
  }

  function renderThreadShell(fullName, avatar, online) {
    chatThread.innerHTML = '';
    const clone = template.content.cloneNode(true);
    chatThread.appendChild(clone);
    document.getElementById('chatThreadAvatar').src = avatar;
    document.getElementById('chatThreadName').textContent = fullName;
    setThreadPresence(online);
  }

  function setThreadPresence(online) {
    const dot = document.getElementById('chatThreadPresenceDot');
    const status = document.getElementById('chatThreadStatus');
    if (dot) dot.hidden = !online;
    if (status && !status.classList.contains('is-typing')) {
      status.textContent = online ? 'Đang hoạt động' : '';
    }
  }

  function setTypingStatus(isTyping) {
    const status = document.getElementById('chatThreadStatus');
    if (!status) return;
    clearTimeout(typingHideTimer);
    if (isTyping) {
      status.textContent = 'Đang nhập...';
      status.classList.add('is-typing');
      typingHideTimer = setTimeout(() => {
        status.classList.remove('is-typing');
        const dot = document.getElementById('chatThreadPresenceDot');
        status.textContent = (dot && !dot.hidden) ? 'Đang hoạt động' : '';
      }, 3000);
    } else {
      status.classList.remove('is-typing');
      const dot = document.getElementById('chatThreadPresenceDot');
      status.textContent = (dot && !dot.hidden) ? 'Đang hoạt động' : '';
    }
  }

  // ---------- Render lịch sử tin nhắn (nhóm theo ngày + theo cụm người gửi) ----------
  function renderMessages(messages) {
    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '';
    resetClusterTracking();

    if (!messages.length) {
      messageList.innerHTML = '<div class="empty-state empty-state--thread"><p>Chưa có tin nhắn nào</p><span>Gửi lời chào để bắt đầu trò chuyện!</span></div>';
      return;
    }

    messages.forEach((msg, idx) => appendBubble(msg, { isHistory: true, isLast: idx === messages.length - 1 }));
    updateLastMineReadState(messages);
    scrollToBottom(true);
  }

  function dateKeyOf(iso) {
    const d = new Date(iso);
    return d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate();
  }

  function formatDateDivider(iso) {
    const d = new Date(iso);
    const now = new Date();
    const todayKey = dateKeyOf(now.toISOString());
    const yestKey = dateKeyOf(new Date(now.getTime() - 86400000).toISOString());
    const key = dateKeyOf(iso);
    if (key === todayKey) return 'Hôm nay';
    if (key === yestKey) return 'Hôm qua';
    return d.toLocaleDateString('vi-VN');
  }

  function formatClock(iso) {
    const d = new Date(iso);
    return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
  }

  function maybeInsertDateDivider(sentAt) {
    const messageList = document.getElementById('messageList');
    const key = dateKeyOf(sentAt);
    if (key !== lastRenderedDateKey) {
      lastRenderedDateKey = key;
      const divider = document.createElement('div');
      divider.className = 'msg-date-divider';
      divider.innerHTML = '<span>' + formatDateDivider(sentAt) + '</span>';
      messageList.appendChild(divider);
      return true;
    }
    return false;
  }

  /**
   * Thêm 1 bubble vào cuối khung chat. Tự gộp cụm với bubble liền trước nếu cùng
   * người gửi và cách nhau dưới 5 phút (dời mốc giờ từ bubble cũ sang bubble mới),
   * kèm avatar cho bubble ĐẦU cụm bên "theirs" giống Threads/Messenger.
   */
  function appendBubble(msg, opts) {
    opts = opts || {};
    const messageList = document.getElementById('messageList');
    const emptyEl = messageList.querySelector('.empty-state');
    if (emptyEl) emptyEl.remove();

    const mine = String(msg.senderId) === String(currentUserId);
    const sentAt = msg.sentAt || new Date().toISOString();
    const sentAtMs = new Date(sentAt).getTime();

    const dividerInserted = maybeInsertDateDivider(sentAt);
    const sameCluster = !dividerInserted && lastRenderedSenderId === String(msg.senderId) &&
        (sentAtMs - lastRenderedAt) < 5 * 60 * 1000;

    // Bubble liền trước không còn là cuối cụm nữa -> bỏ mốc giờ của nó đi
    if (sameCluster && lastRenderedBubbleEl) {
      const oldTime = lastRenderedBubbleEl.querySelector('.bubble__time');
      if (oldTime) oldTime.remove();
      lastRenderedBubbleEl.classList.remove('bubble--cluster-last');
    }

    const row = document.createElement('div');
    row.className = 'msg-row ' + (mine ? 'msg-row--mine' : 'msg-row--theirs') + (sameCluster ? ' msg-row--grouped' : '');
    row.dataset.messageId = msg.id || '';
    if (msg.clientId) row.dataset.clientId = msg.clientId;

    let avatarHtml = '';
    if (!mine) {
      const showAvatar = !sameCluster;
      avatarHtml = showAvatar
        ? '<img class="msg-row__avatar" src="' + Aloute.escapeHtml(msg.senderAvatarUrl || 'https://i.pravatar.cc/150?img=32') + '" alt="avatar">'
        : '<span class="msg-row__avatar-spacer"></span>';
    }

    const pendingClass = opts.pending ? ' bubble--pending' : '';
    row.innerHTML =
      avatarHtml +
      '<div class="bubble ' + (mine ? 'bubble--mine' : 'bubble--theirs') + ' bubble--cluster-last' + pendingClass + '">' +
      '  <span class="bubble__text">' + Aloute.escapeHtml(msg.content) + '</span>' +
      '  <span class="bubble__time">' + formatClock(sentAt) + '</span>' +
      '</div>';

    messageList.appendChild(row);

    lastRenderedSenderId = String(msg.senderId);
    lastRenderedAt = sentAtMs;
    lastRenderedBubbleEl = row.querySelector('.bubble');

    if (mine) lastMineBubbleEl = row;

    if (!opts.isHistory) scrollToBottom(false);
    return row;
  }

  function updateLastMineReadState(messages) {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (String(messages[i].senderId) === String(currentUserId)) {
        setReadReceiptText(messages[i].read ? 'Đã xem' : '');
        return;
      }
    }
  }

  function setReadReceiptText(text) {
    if (!lastMineBubbleEl) return;
    let receipt = lastMineBubbleEl.querySelector('.msg-row__receipt');
    if (!text) {
      if (receipt) receipt.remove();
      return;
    }
    if (!receipt) {
      receipt = document.createElement('div');
      receipt.className = 'msg-row__receipt';
      lastMineBubbleEl.appendChild(receipt);
    }
    receipt.textContent = text;
  }

  function scrollToBottom(force) {
    const messageList = document.getElementById('messageList');
    if (!messageList) return;
    const nearBottom = messageList.scrollHeight - messageList.scrollTop - messageList.clientHeight < 160;
    if (force || nearBottom) {
      messageList.scrollTop = messageList.scrollHeight;
    }
  }

  // ---------- Chỉ báo "đang nhập" (typing) ----------
  function notifyTyping() {
    if (!stompClientRef || !activeUserId) return;
    const now = Date.now();
    if (now - lastTypingSentAt < 2000) return;
    lastTypingSentAt = now;
    stompClientRef.send('/app/chat.typing', {}, JSON.stringify({ receiverId: Number(activeUserId) }));
  }

  // ---------- Gửi tin nhắn (optimistic UI) ----------
  function sendCurrentMessage() {
    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendMessageBtn');
    const content = input.value.trim();
    if (!content || !activeUserId) return;

    if (!stompClientRef) {
      Aloute.toast('Đang kết nối lại, vui lòng thử lại sau giây lát', true);
      return;
    }

    const clientId = 'c' + Date.now() + Math.random().toString(36).slice(2, 8);
    const receiverId = Number(activeUserId);

    const optimisticRow = appendBubble({
      senderId: currentUserId,
      content: content,
      sentAt: new Date().toISOString(),
      clientId: clientId
    }, { pending: true });

    const timeoutId = setTimeout(() => markPendingFailed(clientId), 12000);
    pendingByClientId.set(clientId, { el: optimisticRow, content, receiverId, timeoutId });

    stompClientRef.send('/app/chat.send', {}, JSON.stringify({
      receiverId: receiverId,
      content: content,
      clientId: clientId
    }));

    input.value = '';
    sendBtn.disabled = true;
  }

  function markPendingFailed(clientId) {
    const pending = pendingByClientId.get(clientId);
    if (!pending) return;
    pendingByClientId.delete(clientId);
    const bubble = pending.el.querySelector('.bubble');
    if (bubble) {
      bubble.classList.remove('bubble--pending');
      bubble.classList.add('bubble--failed');
      const timeEl = bubble.querySelector('.bubble__time');
      if (timeEl) timeEl.textContent = 'Gửi thất bại · nhấn để gửi lại';
      bubble.addEventListener('click', () => retryPending(pending), { once: true });
    }
  }

  function retryPending(pending) {
    pending.el.remove();
    const input = document.getElementById('messageInput');
    input.value = pending.content;
    input.dispatchEvent(new Event('input'));
    input.focus();
  }

  function reconcilePending(msg) {
    const pending = pendingByClientId.get(msg.clientId);
    if (!pending) return false;
    clearTimeout(pending.timeoutId);
    pendingByClientId.delete(msg.clientId);
    const bubble = pending.el.querySelector('.bubble');
    if (bubble) {
      bubble.classList.remove('bubble--pending');
      const timeEl = bubble.querySelector('.bubble__time');
      if (timeEl) timeEl.textContent = formatClock(msg.sentAt);
    }
    pending.el.dataset.messageId = msg.id || '';
    return true;
  }

  // ---------- Cập nhật/khởi tạo dòng hội thoại ở cột trái khi có tin nhắn mới ----------
  function upsertChatListItem(msg, otherId) {
    let item = chatList.querySelector('.chat-item[data-user-id="' + otherId + '"]');
    const isMine = String(msg.senderId) === String(currentUserId);

    if (!item) {
      location.reload();
      return;
    }

    item.classList.remove('chat-item--preview');
    item.dataset.hasConversation = 'true';

    let preview = item.querySelector('.chat-item__preview');
    if (!preview || preview.classList.contains('chat-item__preview--new')) {
      const bottom = item.querySelector('.chat-item__bottom');
      if (preview) preview.remove();
      preview = document.createElement('span');
      preview.className = 'chat-item__preview';
      bottom.insertBefore(preview, bottom.firstChild);
    }
    preview.textContent = msg.content;

    let timeEl = item.querySelector('.chat-item__time');
    const top = item.querySelector('.chat-item__top');
    if (!timeEl) {
      timeEl = document.createElement('span');
      timeEl.className = 'chat-item__time';
      top.appendChild(timeEl);
    }
    timeEl.textContent = 'vừa xong';

    const isActive = activeUserId && String(otherId) === String(activeUserId);
    if (!isMine && !isActive) {
      let badge = item.querySelector('.chat-item__unread');
      if (!badge) {
        badge = document.createElement('span');
        badge.className = 'chat-item__unread';
        item.querySelector('.chat-item__bottom').appendChild(badge);
        badge.textContent = '1';
      } else {
        badge.textContent = String((parseInt(badge.textContent, 10) || 0) + 1);
      }
    }

    // Đưa hội thoại vừa có tin nhắn mới lên đầu danh sách, giống Threads/Messenger
    if (chatListScroll && chatListScroll.firstChild !== item) {
      chatListScroll.insertBefore(item, chatListScroll.firstChild);
    }
  }

  function setItemOnline(userId, online) {
    const item = chatList.querySelector('.chat-item[data-user-id="' + userId + '"]');
    if (!item) return;
    item.dataset.online = online ? 'true' : 'false';
    const wrap = item.querySelector('.chat-item__avatar-wrap');
    if (!wrap) return;
    let dot = wrap.querySelector('.presence-dot');
    if (online && !dot) {
      dot = document.createElement('span');
      dot.className = 'presence-dot';
      wrap.appendChild(dot);
    } else if (!online && dot) {
      dot.remove();
    }
  }

  // ---------- Nhận tin nhắn real-time (app.js phát ra sự kiện này) ----------
  document.addEventListener('aloute:message', (e) => {
    const msg = e.detail;
    const otherId = String(msg.senderId) === String(currentUserId) ? String(msg.receiverId) : String(msg.senderId);
    const isMine = String(msg.senderId) === String(currentUserId);

    if (isMine && msg.clientId && reconcilePending(msg)) {
      upsertChatListItem(msg, otherId);
      return;
    }

    if (activeUserId && otherId === String(activeUserId)) {
      setTypingStatus(false);
      const row = appendBubble(msg, {});
      if (isMine) lastMineBubbleEl = row;
      if (!isMine) {
        setReadReceiptText('');
        Aloute.api('/api/chat/read/' + otherId, { method: 'POST' }).catch(() => {});
      }
    }

    upsertChatListItem(msg, otherId);
  });

  // ---------- Đang gõ (app.js phát 'aloute:typing' từ /queue/typing) ----------
  document.addEventListener('aloute:typing', (e) => {
    const senderId = String(e.detail.senderId);
    if (activeUserId && senderId === String(activeUserId)) {
      setTypingStatus(true);
    }
  });

  // ---------- Đã xem (app.js phát 'aloute:read' từ /queue/read-receipts) ----------
  document.addEventListener('aloute:read', (e) => {
    const readerId = String(e.detail.readerId);
    if (activeUserId && readerId === String(activeUserId)) {
      setReadReceiptText('Đã xem');
    }
  });

  // ---------- Online/offline (app.js phát 'aloute:presence' từ /queue/presence) ----------
  document.addEventListener('aloute:presence', (e) => {
    const { userId, online } = e.detail;
    setItemOnline(userId, online);
    if (activeUserId && String(userId) === String(activeUserId)) {
      setThreadPresence(online);
    }
  });

  // ---------- Lỗi gửi tin nhắn (app.js phát 'aloute:chat-error' từ /queue/errors) ----------
  document.addEventListener('aloute:chat-error', (e) => {
    const { message, clientId } = e.detail;
    Aloute.toast(message || 'Không gửi được tin nhắn', true);
    if (clientId && pendingByClientId.has(clientId)) {
      const pending = pendingByClientId.get(clientId);
      clearTimeout(pending.timeoutId);
      pendingByClientId.delete(clientId);
      pending.el.remove();
      const input = document.getElementById('messageInput');
      if (input) {
        input.value = pending.content;
        input.dispatchEvent(new Event('input'));
      }
    }
  });
})();