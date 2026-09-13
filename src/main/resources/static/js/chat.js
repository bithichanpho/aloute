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
  const chatShell = document.getElementById('chatShell');
  const chatThread = document.getElementById('chatThread');
  const emptyState = document.getElementById('chatEmptyState');
  const template = document.getElementById('chatThreadTemplate');
  const searchInput = document.getElementById('chatSearchInput');
  if (!chatList || !chatThread || !template) return;

  const currentUserId = document.body.dataset.currentUserId;

  let activeUserId = null;
  let activeGroupId = null;
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

  // ---------- Chọn 1 hội thoại (1-1 hoặc nhóm) ----------
  chatList.querySelectorAll('.chat-item[data-user-id], .chat-item[data-group-id]').forEach(item => {
    item.addEventListener('click', () => openConversation(item));
  });

  // ---------- Đến từ nút "Nhắn tin" trên trang cá nhân (/chat?userId=X) -> mở sẵn đúng hội thoại ----------
  if (chatShell && chatShell.dataset.openUserId) {
    const targetItem = chatList.querySelector('.chat-item[data-user-id="' + chatShell.dataset.openUserId + '"]');
    if (targetItem) {
      openConversation(targetItem);
      targetItem.scrollIntoView({ block: 'nearest' });
    }
  }

  function openConversation(item) {
    if (item.dataset.groupId) {
      openGroupConversation(item);
    } else {
      openDirectConversation(item);
    }
  }

  async function openDirectConversation(item) {
    chatList.querySelectorAll('.chat-item').forEach(i => i.classList.remove('chat-item--active'));
    item.classList.add('chat-item--active');
    chatList.classList.add('has-active-thread');
    activeItemEl = item;

    const userId = item.dataset.userId;
    const fullName = item.dataset.fullname || item.dataset.username || 'Người dùng';
    const avatar = item.dataset.avatar || 'https://i.pravatar.cc/150?img=32';
    const online = item.dataset.online === 'true';
    activeGroupId = null;
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

  // ---------- Mở 1 luồng chat NHÓM ----------
  async function openGroupConversation(item) {
    chatList.querySelectorAll('.chat-item').forEach(i => i.classList.remove('chat-item--active'));
    item.classList.add('chat-item--active');
    chatList.classList.add('has-active-thread');
    activeItemEl = item;

    const groupId = item.dataset.groupId;
    const groupName = item.dataset.groupName || 'Nhóm';
    const avatar = item.dataset.avatar || ('https://api.dicebear.com/7.x/identicon/svg?seed=' + groupId);
    activeUserId = null;
    activeGroupId = groupId;

    resetClusterTracking();

    const unreadEl = item.querySelector('.chat-item__unread');
    if (unreadEl) unreadEl.remove();

    renderThreadShell(groupName, avatar, false);
    const threadAvatar = document.getElementById('chatThreadAvatar');
    if (threadAvatar) threadAvatar.classList.add('chat-item__avatar--group');

    const statusEl = document.getElementById('chatThreadStatus');
    if (statusEl) statusEl.textContent = (item.dataset.memberCount || '0') + ' thành viên';

    const menuBtn = document.getElementById('groupMenuBtn');
    if (menuBtn) {
      menuBtn.hidden = false;
      menuBtn.addEventListener('click', () => openGroupInfoModal(groupId));
    }

    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Đang tải tin nhắn...</span></div>';

    try {
      const messages = await Aloute.api('/api/chat/group/' + groupId + '/messages');
      renderMessages(messages);
      Aloute.api('/api/chat/group/' + groupId + '/read', { method: 'POST' }).catch(() => {});
    } catch (err) {
      messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Không tải được tin nhắn: ' + Aloute.escapeHtml(err.message) + '</span></div>';
    }

    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendMessageBtn');

    input.addEventListener('input', () => {
      sendBtn.disabled = input.value.trim().length === 0;
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
    let senderNameHtml = '';
    if (!mine) {
      const showAvatar = !sameCluster;
      avatarHtml = showAvatar
        ? '<img class="msg-row__avatar" src="' + Aloute.escapeHtml(msg.senderAvatarUrl || 'https://i.pravatar.cc/150?img=32') + '" alt="avatar">'
        : '<span class="msg-row__avatar-spacer"></span>';
      // Trong nhóm có nhiều người gửi khác nhau -> ghi rõ tên ngay trong bubble đầu cụm để khỏi nhầm ai nói gì
      if (activeGroupId && showAvatar) {
        senderNameHtml = '<span class="bubble__sender-name">' + Aloute.escapeHtml(msg.senderFullName || msg.senderUsername || '') + '</span>';
      }
    }

    const pendingClass = opts.pending ? ' bubble--pending' : '';
    row.innerHTML =
      avatarHtml +
      '<div class="bubble ' + (mine ? 'bubble--mine' : 'bubble--theirs') + ' bubble--cluster-last' + pendingClass + '">' +
      senderNameHtml +
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

  // ---------- Gửi tin nhắn (optimistic UI, hỗ trợ cả 1-1 và nhóm) ----------
  function sendCurrentMessage() {
    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendMessageBtn');
    const content = input.value.trim();
    if (!content || (!activeUserId && !activeGroupId)) return;

    if (!stompClientRef) {
      Aloute.toast('Đang kết nối lại, vui lòng thử lại sau giây lát', true);
      return;
    }

    const clientId = 'c' + Date.now() + Math.random().toString(36).slice(2, 8);
    const isGroup = !!activeGroupId;

    const optimisticRow = appendBubble({
      senderId: currentUserId,
      content: content,
      sentAt: new Date().toISOString(),
      clientId: clientId
    }, { pending: true });

    const timeoutId = setTimeout(() => markPendingFailed(clientId), 12000);
    pendingByClientId.set(clientId, isGroup
      ? { el: optimisticRow, content, groupId: Number(activeGroupId), timeoutId }
      : { el: optimisticRow, content, receiverId: Number(activeUserId), timeoutId });

    const payload = isGroup
      ? { groupId: Number(activeGroupId), content: content, clientId: clientId }
      : { receiverId: Number(activeUserId), content: content, clientId: clientId };
    stompClientRef.send('/app/chat.send', {}, JSON.stringify(payload));

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

  /** Cập nhật dòng NHÓM ở cột trái khi có tin nhắn nhóm mới — tương tự upsertChatListItem nhưng cho group. */
  function upsertGroupListItem(msg) {
    const item = chatList.querySelector('.chat-item[data-group-id="' + msg.groupId + '"]');
    const isMine = String(msg.senderId) === String(currentUserId);

    if (!item) {
      location.reload();
      return;
    }

    let preview = item.querySelector('.chat-item__preview');
    if (preview) {
      const senderPrefix = isMine ? 'Bạn: ' : (msg.senderFullName || msg.senderUsername || '') + ': ';
      preview.textContent = senderPrefix + msg.content;
    }

    const timeEl = item.querySelector('.chat-item__time');
    if (timeEl) timeEl.textContent = 'vừa xong';

    const isActive = activeGroupId && String(msg.groupId) === String(activeGroupId);
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

    if (chatListScroll && chatListScroll.firstChild !== item) {
      chatListScroll.insertBefore(item, chatListScroll.firstChild);
    }
  }

  // ---------- Nhận tin nhắn real-time (app.js phát ra sự kiện này) ----------
  document.addEventListener('aloute:message', (e) => {
    const msg = e.detail;
    const isMine = String(msg.senderId) === String(currentUserId);

    if (msg.groupId != null) {
      handleIncomingGroupMessage(msg, isMine);
      return;
    }

    const otherId = isMine ? String(msg.receiverId) : String(msg.senderId);

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

  function handleIncomingGroupMessage(msg, isMine) {
    if (isMine && msg.clientId && reconcilePending(msg)) {
      upsertGroupListItem(msg);
      return;
    }

    if (activeGroupId && String(msg.groupId) === String(activeGroupId)) {
      appendBubble(msg, {});
      if (!isMine) {
        Aloute.api('/api/chat/group/' + msg.groupId + '/read', { method: 'POST' }).catch(() => {});
      }
    }

    upsertGroupListItem(msg);
  }

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

  // ============================================================
  // Quản lý nhóm chat: tạo nhóm mới, xem/thêm/xóa thành viên, rời nhóm.
  // ============================================================

  const newGroupBtn = document.getElementById('newGroupBtn');
  const createGroupModal = document.getElementById('createGroupModal');
  const closeCreateGroupBtn = document.getElementById('closeCreateGroupBtn');
  const createGroupForm = document.getElementById('createGroupForm');
  const newGroupNameInput = document.getElementById('newGroupName');
  const newGroupMemberList = document.getElementById('newGroupMemberList');
  const createGroupError = document.getElementById('createGroupError');
  const createGroupSubmitBtn = document.getElementById('createGroupSubmitBtn');

  const groupInfoModal = document.getElementById('groupInfoModal');
  const closeGroupInfoBtn = document.getElementById('closeGroupInfoBtn');
  const groupInfoTitle = document.getElementById('groupInfoTitle');
  const groupInfoError = document.getElementById('groupInfoError');
  const groupInfoMemberList = document.getElementById('groupInfoMemberList');
  const groupAddMemberList = document.getElementById('groupAddMemberList');
  const leaveGroupBtn = document.getElementById('leaveGroupBtn');

  let myFriendsCache = null; // cache danh sách bạn bè trong phiên này, đỡ gọi lại API nhiều lần

  async function getMyFriends() {
    if (myFriendsCache) return myFriendsCache;
    myFriendsCache = await Aloute.api('/api/users/' + currentUserId + '/friends');
    return myFriendsCache;
  }

  function openModal(modal) { if (modal) modal.hidden = false; }
  function closeModal(modal) { if (modal) modal.hidden = true; }

  // ---------- Modal: Tạo nhóm mới ----------
  if (newGroupBtn) {
    newGroupBtn.addEventListener('click', async () => {
      createGroupError.style.display = 'none';
      createGroupForm.reset();
      newGroupMemberList.innerHTML = '<span style="color:var(--text-tertiary); font-size:13.5px;">Đang tải danh sách bạn bè...</span>';
      openModal(createGroupModal);

      try {
        const friends = await getMyFriends();
        if (!friends.length) {
          newGroupMemberList.innerHTML = '<span style="color:var(--text-tertiary); font-size:13.5px;">Bạn chưa có bạn bè nào (2 người cần follow lẫn nhau) để thêm vào nhóm.</span>';
          return;
        }
        newGroupMemberList.innerHTML = friends.map(f => memberRowHtml(f, { checkbox: true })).join('');
      } catch (err) {
        newGroupMemberList.innerHTML = '';
        createGroupError.textContent = err.message || 'Không tải được danh sách bạn bè';
        createGroupError.style.display = 'block';
      }
    });
  }

  if (closeCreateGroupBtn) closeCreateGroupBtn.addEventListener('click', () => closeModal(createGroupModal));
  if (createGroupModal) {
    createGroupModal.addEventListener('click', (e) => { if (e.target === createGroupModal) closeModal(createGroupModal); });
  }

  if (createGroupForm) {
    createGroupForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      createGroupError.style.display = 'none';

      const name = newGroupNameInput.value.trim();
      const memberIds = Array.from(newGroupMemberList.querySelectorAll('input[type="checkbox"]:checked'))
          .map(cb => Number(cb.value));

      if (!name) {
        createGroupError.textContent = 'Vui lòng nhập tên nhóm.';
        createGroupError.style.display = 'block';
        return;
      }
      if (!memberIds.length) {
        createGroupError.textContent = 'Chọn ít nhất 1 bạn bè để tạo nhóm.';
        createGroupError.style.display = 'block';
        return;
      }

      createGroupSubmitBtn.disabled = true;
      createGroupSubmitBtn.textContent = 'Đang tạo...';

      try {
        await Aloute.api('/api/chat/groups', {
          method: 'POST',
          body: JSON.stringify({ name, memberIds })
        });
        Aloute.toast('Đã tạo nhóm "' + name + '"');
        closeModal(createGroupModal);
        // Danh sách bên trái cần thêm dòng nhóm mới -> tải lại trang là cách chắc chắn nhất
        location.reload();
      } catch (err) {
        createGroupError.textContent = err.message || 'Tạo nhóm thất bại';
        createGroupError.style.display = 'block';
      } finally {
        createGroupSubmitBtn.disabled = false;
        createGroupSubmitBtn.textContent = 'Tạo nhóm';
      }
    });
  }

  function memberRowHtml(user, opts) {
    opts = opts || {};
    const avatar = user.avatarUrl || 'https://i.pravatar.cc/150?img=32';
    const name = user.fullName || user.username;
    let right = '';
    if (opts.checkbox) {
      right = '<input type="checkbox" value="' + user.id + '">';
    } else if (opts.roleLabel) {
      right = '<span class="member-row__role">' + Aloute.escapeHtml(opts.roleLabel) + '</span>';
    }
    if (opts.actionLabel) {
      right += '<button type="button" class="member-row__action" data-action-user-id="' + user.id + '">' + Aloute.escapeHtml(opts.actionLabel) + '</button>';
    }
    return '<label class="member-row">' +
      '<img src="' + Aloute.escapeHtml(avatar) + '" alt="avatar">' +
      '<span class="member-row__name">' + Aloute.escapeHtml(name) + '</span>' +
      right +
      '</label>';
  }

  // ---------- Modal: Thông tin & thành viên nhóm ----------
  async function openGroupInfoModal(groupId) {
    groupInfoError.style.display = 'none';
    groupInfoTitle.textContent = 'Thông tin nhóm';
    groupInfoMemberList.innerHTML = '<span style="color:var(--text-tertiary); font-size:13.5px;">Đang tải...</span>';
    groupAddMemberList.innerHTML = '<span style="color:var(--text-tertiary); font-size:13.5px;">Đang tải...</span>';
    groupInfoModal.dataset.groupId = groupId;
    openModal(groupInfoModal);
    await refreshGroupInfoModal(groupId);
  }

  async function refreshGroupInfoModal(groupId) {
    try {
      const group = await Aloute.api('/api/chat/groups/' + groupId);
      groupInfoTitle.textContent = group.name + ' · ' + group.members.length + ' thành viên';

      const myMembership = group.members.find(m => String(m.userId) === String(currentUserId));
      const iAmAdmin = myMembership && myMembership.role === 'ADMIN';

      groupInfoMemberList.innerHTML = group.members.map(m => memberRowHtml(
        { id: m.userId, fullName: m.fullName, username: m.username, avatarUrl: m.avatarUrl },
        {
          roleLabel: m.role === 'ADMIN' ? 'Quản trị viên' : '',
          actionLabel: (iAmAdmin && String(m.userId) !== String(currentUserId)) ? 'Xóa' : ''
        }
      )).join('');

      groupInfoMemberList.querySelectorAll('.member-row__action').forEach(btn => {
        btn.addEventListener('click', async () => {
          const targetUserId = btn.dataset.actionUserId;
          if (!confirm('Xóa thành viên này khỏi nhóm?')) return;
          try {
            await Aloute.api('/api/chat/groups/' + groupId + '/members/' + targetUserId, { method: 'DELETE' });
            Aloute.toast('Đã xóa thành viên');
            await refreshGroupInfoModal(groupId);
            updateGroupListItemMemberCount(groupId, group.members.length - 1);
          } catch (err) {
            groupInfoError.textContent = err.message || 'Xóa thành viên thất bại';
            groupInfoError.style.display = 'block';
          }
        });
      });

      // Bạn bè chưa ở trong nhóm -> gợi ý thêm nhanh
      const memberIdSet = new Set(group.members.map(m => String(m.userId)));
      const friends = await getMyFriends();
      const addable = friends.filter(f => !memberIdSet.has(String(f.id)));

      groupAddMemberList.innerHTML = addable.length
        ? addable.map(f => memberRowHtml(f, { actionLabel: 'Thêm' })).join('')
        : '<span style="color:var(--text-tertiary); font-size:13.5px;">Không còn bạn bè nào để thêm.</span>';

      groupAddMemberList.querySelectorAll('.member-row__action').forEach(btn => {
        btn.addEventListener('click', async () => {
          const userId = btn.dataset.actionUserId;
          btn.disabled = true;
          try {
            await Aloute.api('/api/chat/groups/' + groupId + '/members', {
              method: 'POST',
              body: JSON.stringify({ userId: Number(userId) })
            });
            Aloute.toast('Đã thêm thành viên');
            await refreshGroupInfoModal(groupId);
            updateGroupListItemMemberCount(groupId, group.members.length + 1);
          } catch (err) {
            groupInfoError.textContent = err.message || 'Thêm thành viên thất bại';
            groupInfoError.style.display = 'block';
            btn.disabled = false;
          }
        });
      });
    } catch (err) {
      groupInfoMemberList.innerHTML = '';
      groupInfoError.textContent = err.message || 'Không tải được thông tin nhóm';
      groupInfoError.style.display = 'block';
    }
  }

  function updateGroupListItemMemberCount(groupId, count) {
    const item = chatList.querySelector('.chat-item[data-group-id="' + groupId + '"]');
    if (!item) return;
    item.dataset.memberCount = String(count);
    if (activeGroupId && String(groupId) === String(activeGroupId)) {
      const statusEl = document.getElementById('chatThreadStatus');
      if (statusEl) statusEl.textContent = count + ' thành viên';
    }
  }

  if (closeGroupInfoBtn) closeGroupInfoBtn.addEventListener('click', () => closeModal(groupInfoModal));
  if (groupInfoModal) {
    groupInfoModal.addEventListener('click', (e) => { if (e.target === groupInfoModal) closeModal(groupInfoModal); });
  }

  if (leaveGroupBtn) {
    leaveGroupBtn.addEventListener('click', async () => {
      const groupId = groupInfoModal.dataset.groupId;
      if (!groupId) return;
      if (!confirm('Rời khỏi nhóm này?')) return;
      try {
        await Aloute.api('/api/chat/groups/' + groupId + '/members/' + currentUserId, { method: 'DELETE' });
        Aloute.toast('Đã rời nhóm');
        closeModal(groupInfoModal);
        location.reload();
      } catch (err) {
        groupInfoError.textContent = err.message || 'Rời nhóm thất bại';
        groupInfoError.style.display = 'block';
      }
    });
  }

  // ---------- Nhóm vừa thay đổi (app.js phát 'aloute:group-update' từ /queue/group-updates) ----------
  document.addEventListener('aloute:group-update', (e) => {
    const { groupId, action, affectedUserId } = e.detail;
    const isAboutMe = String(affectedUserId) === String(currentUserId);
    const item = chatList.querySelector('.chat-item[data-group-id="' + groupId + '"]');

    // Mình vừa được thêm vào 1 nhóm mới, hoặc vừa bị xóa/tự rời (ở tab khác) khỏi 1 nhóm đang có
    // trong danh sách -> danh sách bên trái cần thêm/bớt hẳn 1 dòng, tải lại trang là cách chắc chắn nhất.
    if (action === 'created' || ((action === 'member_removed' || action === 'member_left') && isAboutMe)) {
      location.reload();
      return;
    }

    if (!item) return; // nhóm này không ở trong danh sách của mình (không liên quan) -> bỏ qua

    // Đang mở đúng modal thông tin của nhóm này -> làm mới danh sách thành viên ngay
    if (groupInfoModal && !groupInfoModal.hidden && String(groupInfoModal.dataset.groupId) === String(groupId)) {
      refreshGroupInfoModal(groupId);
    }

    // Người khác (không phải mình) rời/bị thêm/bị xóa -> chỉ cần cập nhật lại số lượng thành viên hiển thị
    if (action === 'member_added' || action === 'member_removed' || action === 'member_left') {
      Aloute.api('/api/chat/groups/' + groupId).then(group => {
        updateGroupListItemMemberCount(groupId, group.members.length);
      }).catch(() => {});
    }
  });
})();