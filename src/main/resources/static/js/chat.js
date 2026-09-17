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
  const chatNotes = document.getElementById('chatNotes');
  const chatNotesTrack = document.getElementById('chatNotesTrack');
  const chatNotesOnlineCount = document.getElementById('chatNotesOnlineCount');
  const chatDetails = document.getElementById('chatDetails');
  const chatDetailsAvatar = document.getElementById('chatDetailsAvatar');
  const chatDetailsName = document.getElementById('chatDetailsName');
  const chatDetailsStatus = document.getElementById('chatDetailsStatus');
  const chatDetailsProfileLink = document.getElementById('chatDetailsProfileLink');
  const chatDetailsPresenceDot = document.getElementById('chatDetailsPresenceDot');
  const chatDetailsSearchBtn = document.getElementById('chatDetailsSearchBtn');
  const chatDetailsMediaBtn = document.getElementById('chatDetailsMediaBtn');
  const chatDetailsMoreBtn = document.getElementById('chatDetailsMoreBtn');
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

  // Media capture
  let captureStream = null;
  let mediaRecorder = null;
  let recordedChunks = [];
  let captureMode = null; // photo | video

  // 1-1 WebRTC call
  let peerConnection = null;
  let localStream = null;
  let activeCallId = null;
  let callPeerUserId = null;
  let pendingIncomingOffer = null;
  let activeCallType = 'video';
  let outgoingCallTimer = null;
  let incomingCallTimer = null;
  const CALL_TIMEOUT_MS = 30 * 1000;
  const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024; // 2GB
  const rtcConfig = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
  const pendingIceCandidates = [];

  Aloute.onRealtimeReady(function (client) {
    stompClientRef = client;
  });

  // ---------- Các nút ở cột phải ----------
  if (chatDetailsSearchBtn) {
    chatDetailsSearchBtn.addEventListener('click', () => {
      if (searchInput) {
        searchInput.focus();
        searchInput.select();
      }
    });
  }

  if (chatDetailsMediaBtn) {
    chatDetailsMediaBtn.addEventListener('click', () => {
      const media = document.querySelector('#messageList .bubble__media');
      if (media) {
        media.scrollIntoView({ behavior: 'smooth', block: 'center' });
      } else if (window.Aloute && typeof Aloute.toast === 'function') {
        Aloute.toast('Chưa có ảnh hoặc video trong đoạn chat này.');
      }
    });
  }

  if (chatDetailsMoreBtn) {
    chatDetailsMoreBtn.addEventListener('click', () => {
      if (window.Aloute && typeof Aloute.toast === 'function') {
        Aloute.toast('Các tùy chọn khác sẽ được bổ sung trong phiên bản tiếp theo.');
      }
    });
  }

  // ---------- Instagram-style Notes / online friends ----------
  // Backend hiện đã có /api/users/{id}/friends và /api/chat/online/{id}.
  // Ta dùng bio ngắn của bạn bè làm nội dung Note hiển thị trong chat hub,
  // chỉ đưa lên strip những người đang online và có nội dung để chia sẻ.
  async function loadChatNotes() {
    if (!chatNotes || !chatNotesTrack || !currentUserId || !window.Aloute || typeof Aloute.api !== 'function') return;

    try {
      const friends = await Aloute.api('/api/users/' + encodeURIComponent(currentUserId) + '/friends');
      const list = Array.isArray(friends) ? friends : [];

      const onlineStates = await Promise.all(list.map(async friend => {
        const existing = chatList && chatList.querySelector('.chat-item[data-user-id="' + String(friend.id) + '"]');
        const knownOnline = existing ? existing.dataset.online === 'true' : null;
        let online = knownOnline;
        if (online === null) {
          try { online = !!(await Aloute.api('/api/chat/online/' + encodeURIComponent(friend.id))); }
          catch (_) { online = false; }
        }
        return { friend, online };
      }));

      const notes = onlineStates
        .filter(x => x.online && x.friend && x.friend.bio && String(x.friend.bio).trim())
        .slice(0, 8);

      chatNotesTrack.innerHTML = '';
      if (!notes.length) {
        chatNotes.hidden = true;
        if (chatNotesOnlineCount) chatNotesOnlineCount.textContent = '';
        return;
      }

      notes.forEach(({ friend }) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'chat-note';
        item.setAttribute('role', 'listitem');
        item.dataset.userId = String(friend.id);
        item.dataset.username = friend.username || '';
        item.title = friend.bio || '';

        const avatar = document.createElement('span');
        avatar.className = 'chat-note__avatar-wrap';
        avatar.innerHTML = '<img class="chat-note__avatar" alt="">' + '<span class="chat-note__online-dot"></span>';
        const img = avatar.querySelector('img');
        img.src = friend.avatarUrl || '/images/avatar_default.png';

        const bubble = document.createElement('span');
        bubble.className = 'chat-note__bubble';
        bubble.textContent = String(friend.bio).trim();

        const name = document.createElement('span');
        name.className = 'chat-note__name';
        name.textContent = friend.fullName || friend.username || 'Bạn bè';

        item.appendChild(avatar);
        item.appendChild(bubble);
        item.appendChild(name);
        item.addEventListener('click', () => {
          const target = chatList && chatList.querySelector('.chat-item[data-user-id="' + String(friend.id) + '"]');
          if (target) {
            openConversation(target);
            target.scrollIntoView({ block: 'nearest' });
          } else if (friend.id) {
            window.location.href = '/chat?userId=' + encodeURIComponent(friend.id);
          }
        });
        chatNotesTrack.appendChild(item);
      });

      chatNotes.hidden = false;
      if (chatNotesOnlineCount) {
        chatNotesOnlineCount.textContent = notes.length + ' đang hoạt động';
      }
    } catch (err) {
      // Notes là lớp UI phụ; không được phép làm hỏng luồng chat chính.
      chatNotes.hidden = true;
    }
  }


  // ---------- Sắp xếp hội thoại theo tin nhắn mới nhất ----------
  function chatItemTime(item) {
    const raw = item && item.dataset ? item.dataset.lastMessageAt : '';
    if (!raw) return -Infinity;
    const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T');
    const t = Date.parse(normalized);
    return Number.isNaN(t) ? -Infinity : t;
  }

  function sortChatListItems() {
    if (!chatListScroll) return;
    const items = Array.from(chatListScroll.querySelectorAll('.chat-item[data-user-id], .chat-item[data-group-id]'));
    items.sort((a, b) => {
      const diff = chatItemTime(b) - chatItemTime(a);
      if (diff !== 0) return diff;
      const an = (a.dataset.fullname || a.dataset.groupName || '').toLowerCase();
      const bn = (b.dataset.fullname || b.dataset.groupName || '').toLowerCase();
      return an.localeCompare(bn, 'vi');
    });
    const fragment = document.createDocumentFragment();
    items.forEach(item => fragment.appendChild(item));
    chatListScroll.appendChild(fragment);
  }

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

  // Đồng bộ thứ tự ngay khi tải trang: mới nhất ở trên cùng, giống Messenger.
  sortChatListItems();
  loadChatNotes();

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

  let historyRequestVersion = 0;

  async function openDirectConversation(item) {
    const requestVersion = ++historyRequestVersion;
    chatList.querySelectorAll('.chat-item').forEach(i => i.classList.remove('chat-item--active'));
    item.classList.add('chat-item--active');
    chatList.classList.add('has-active-thread');
    activeItemEl = item;

    const userId = item.dataset.userId;
    const fullName = item.dataset.fullname || item.dataset.username || 'Người dùng';
    const avatar = item.dataset.avatar || '/images/avatar_default.png';
    const online = item.dataset.online === 'true';
    activeGroupId = null;
    activeUserId = userId;

    resetClusterTracking();

    // Xóa badge chưa đọc của hội thoại này ngay khi mở
    const unreadEl = item.querySelector('.chat-item__unread');
    if (unreadEl) unreadEl.remove();

    renderThreadShell(fullName, avatar, online);
    updateChatDetails({
      name: fullName,
      avatar: avatar,
      online: online,
      profileUrl: '/profile/' + encodeURIComponent(userId),
      isGroup: false
    });
    const videoBtn = document.getElementById('videoCallBtn');
    const audioBtn = document.getElementById('audioCallBtn');
    if (videoBtn) {
      videoBtn.hidden = false;
      videoBtn.onclick = () => startCall(userId, fullName, 'video');
    }
    if (audioBtn) {
      audioBtn.hidden = false;
      audioBtn.onclick = () => startCall(userId, fullName, 'audio');
    }

    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Đang tải tin nhắn...</span></div>';

    try {
      const messages = await Aloute.api('/api/chat/messages/' + userId);
      if (requestVersion !== historyRequestVersion) return;
      renderMessages(messages);
      Aloute.api('/api/chat/read/' + userId, { method: 'POST' }).catch(() => {});
    } catch (err) {
      if (requestVersion !== historyRequestVersion) return;
      messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Không tải được tin nhắn: ' + Aloute.escapeHtml(err.message) + '</span></div>';
    }

    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendMessageBtn');

    input.oninput = () => {
      sendBtn.disabled = input.value.trim().length === 0;
      notifyTyping();
    };
    sendBtn.onclick = () => sendCurrentMessage();
    input.onkeydown = (e) => {
      if (e.key === 'Enter') { e.preventDefault(); sendCurrentMessage(); }
    };
    input.focus();

    const backBtn = document.getElementById('chatBackBtn');
    if (backBtn) {
      backBtn.onclick = () => {
        chatList.classList.remove('has-active-thread');
      };
    }
  }

  // ---------- Mở 1 luồng chat NHÓM ----------
  async function openGroupConversation(item) {
    const requestVersion = ++historyRequestVersion;
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
    updateChatDetails({
      name: groupName,
      avatar: avatar,
      online: false,
      profileUrl: '#',
      isGroup: true,
      groupId: groupId,
      memberCount: item.dataset.memberCount || '0'
    });
    const groupCallBtn = document.getElementById('videoCallBtn');
    const groupAudioBtn = document.getElementById('audioCallBtn');
    if (groupCallBtn) groupCallBtn.hidden = true;
    if (groupAudioBtn) groupAudioBtn.hidden = true;
    const threadAvatar = document.getElementById('chatThreadAvatar');
    if (threadAvatar) threadAvatar.classList.add('chat-item__avatar--group');

    const statusEl = document.getElementById('chatThreadStatus');
    if (statusEl) statusEl.textContent = (item.dataset.memberCount || '0') + ' thành viên';

    const menuBtn = document.getElementById('groupMenuBtn');
    if (menuBtn) {
      menuBtn.hidden = false;
      menuBtn.onclick = () => openGroupInfoModal(groupId);
    }

    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Đang tải tin nhắn...</span></div>';

    try {
      const messages = await Aloute.api('/api/chat/group/' + groupId + '/messages');
      if (requestVersion !== historyRequestVersion) return;
      renderMessages(messages);
      Aloute.api('/api/chat/group/' + groupId + '/read', { method: 'POST' }).catch(() => {});
    } catch (err) {
      if (requestVersion !== historyRequestVersion) return;
      messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Không tải được tin nhắn: ' + Aloute.escapeHtml(err.message) + '</span></div>';
    }

    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendMessageBtn');

    input.oninput = () => {
      sendBtn.disabled = input.value.trim().length === 0;
    };
    sendBtn.onclick = () => sendCurrentMessage();
    input.onkeydown = (e) => {
      if (e.key === 'Enter') { e.preventDefault(); sendCurrentMessage(); }
    };
    input.focus();

    const backBtn = document.getElementById('chatBackBtn');
    if (backBtn) {
      backBtn.onclick = () => {
        chatList.classList.remove('has-active-thread');
      };
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

  function updateChatDetails({ name, avatar, online, profileUrl, isGroup, groupId, memberCount }) {
    if (!chatDetails) return;

    chatDetails.hidden = false;

    if (chatDetailsAvatar) chatDetailsAvatar.src = avatar || '/images/avatar_default.png';
    if (chatDetailsName) chatDetailsName.textContent = name || 'Người dùng';

    if (chatDetailsPresenceDot) chatDetailsPresenceDot.hidden = !!isGroup || !online;
    if (chatDetailsStatus) {
      chatDetailsStatus.textContent = isGroup
        ? ((memberCount || '0') + ' thành viên')
        : (online ? 'Đang hoạt động' : 'Không hoạt động');
    }

    if (chatDetailsProfileLink) {
      if (isGroup) {
        chatDetailsProfileLink.textContent = 'Xem thông tin nhóm';
        chatDetailsProfileLink.href = '#';
        chatDetailsProfileLink.onclick = function (e) {
          e.preventDefault();
          if (groupId) openGroupInfoModal(groupId);
        };
      } else {
        chatDetailsProfileLink.textContent = 'Xem trang cá nhân';
        chatDetailsProfileLink.href = profileUrl || '#';
        chatDetailsProfileLink.onclick = null;
      }
    }
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
  function formatFileSize(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
    return (n / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
  }

  function appendBubble(msg, opts) {
    opts = opts || {};
    const messageList = document.getElementById('messageList');
    if (!messageList) return null;
    const emptyEl = messageList.querySelector('.empty-state');
    if (emptyEl) emptyEl.remove();

    const mine = String(msg.senderId) === String(currentUserId);
    const sentAt = msg.sentAt || new Date().toISOString();
    const sentAtMs = new Date(sentAt).getTime();

    const dividerInserted = maybeInsertDateDivider(sentAt);
    const sameCluster = !dividerInserted && lastRenderedSenderId === String(msg.senderId) &&
        (sentAtMs - lastRenderedAt) < 5 * 60 * 1000;

    // Một message row có thể chứa text bubble, media card hoặc call card.
    // Chúng được render như các sibling độc lập để media/call không bao giờ bị bọc
    // bên trong nền bubble trắng/xám của tin nhắn văn bản.
    if (sameCluster && lastRenderedBubbleEl) {
      const oldTime = lastRenderedBubbleEl.querySelector('.msg-row__timestamp');
      if (oldTime) oldTime.remove();
      lastRenderedBubbleEl.classList.remove('bubble--cluster-last');
    }

    const row = document.createElement('div');
    row.className = 'msg-row ' + (mine ? 'msg-row--mine' : 'msg-row--theirs') + (sameCluster ? ' msg-row--grouped' : '');
    row.dataset.messageId = msg.id || '';
    row.dataset.senderId = msg.senderId || '';
    if (msg.clientId) row.dataset.clientId = msg.clientId;

    let avatarHtml = '';
    let senderNameHtml = '';
    if (!mine) {
      const showAvatar = !sameCluster;
      avatarHtml = showAvatar
        ? '<img class="msg-row__avatar" src="' + Aloute.escapeHtml(msg.senderAvatarUrl || '/images/avatar_default.png') + '" alt="avatar">'
        : '<span class="msg-row__avatar-spacer"></span>';
      if (activeGroupId && showAvatar) {
        senderNameHtml = '<span class="bubble__sender-name">' + Aloute.escapeHtml(msg.senderFullName || msg.senderUsername || '') + '</span>';
      }
    }

    const pendingClass = opts.pending ? ' bubble--pending' : '';
    const isCallMessage = String(msg.messageType || '').toUpperCase() === 'CALL';
    const hasText = String(msg.content || '').trim().length > 0;

    let contentHtml = '';
    if (hasText) {
      contentHtml = '<div class="bubble ' + (mine ? 'bubble--mine' : 'bubble--theirs') + pendingClass + ' bubble--cluster-last">' +
        senderNameHtml +
        '<span class="bubble__text">' + Aloute.escapeHtml(msg.content || '') + '</span>' +
      '</div>';
    } else if (senderNameHtml) {
      // Tên người gửi trong group chat vẫn cần xuất hiện kể cả khi message chỉ có media/call.
      contentHtml += '<div class="msg-row__sender-name">' + senderNameHtml + '</div>';
    }

    let specialCallHtml = '';
    if (isCallMessage) {
      const isAudioCall = String(msg.callType || '').toLowerCase() === 'audio';
      const isMissed = String(msg.callStatus || '').toUpperCase() === 'MISSED';
      const callLabel = isAudioCall ? 'Cuộc gọi thoại' : 'Cuộc gọi video';
      const title = isMissed
        ? (mine ? (callLabel + ' · Không được trả lời') : (callLabel + ' nhỡ'))
        : callLabel;
      const icon = isAudioCall
        ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.8 19.8 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.12.9.33 1.77.62 2.61a2 2 0 0 1-.45 2.11L8 9.73a16 16 0 0 0 6 6l1.29-1.29a2 2 0 0 1 2.11-.45c.84.29 1.71.5 2.61.62A2 2 0 0 1 22 16.92Z"/></svg>'
        : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m23 7-7 5 7 5V7Z"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>';
      const callAgainTarget = isMissed ? (mine ? msg.receiverId : msg.senderId) : null;
      const callAgainAttrs = callAgainTarget
        ? ' data-call-again="' + Aloute.escapeHtml(String(callAgainTarget)) + '" data-call-type="' + (isAudioCall ? 'audio' : 'video') + '" role="button" tabindex="0" aria-label="Gọi lại" title="Nhấn để gọi lại"'
        : '';
      specialCallHtml = '<div class="bubble__call ' + (isMissed ? 'bubble__call--missed' : 'bubble__call--completed') + '"' + callAgainAttrs + '>' +
        '<span class="bubble__call-icon">' + icon + '</span>' +
        '<span class="bubble__call-body"><strong>' + title + '</strong><span>' + formatClock(sentAt) + (isMissed ? ' · ' + (mine ? 'Không có phản hồi' : 'Cuộc gọi đến không được trả lời') : ' · Đã kết thúc') + '</span></span>' +
      '</div>';
    }

    let mediaHtml = '';
    if (msg.mediaUrl && !isCallMessage) {
      const safeUrl = Aloute.escapeHtml(msg.mediaUrl);
      const mediaType = String(msg.mediaType || '').toLowerCase();
      if (mediaType === 'video') {
        mediaHtml = '<a class="chat-attachment chat-attachment--media" href="' + safeUrl + '" target="_blank" rel="noopener" aria-label="Mở video"><video src="' + safeUrl + '" controls playsinline preload="metadata"></video></a>';
      } else if (mediaType === 'file') {
        const name = Aloute.escapeHtml(msg.mediaName || 'Tệp đính kèm');
        const size = formatFileSize(msg.mediaSize);
        mediaHtml = '<a class="chat-attachment chat-attachment--file" href="' + safeUrl + '" target="_blank" rel="noopener" download><span class="bubble__file-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/><path d="M8 13h8M8 17h6"/></svg></span><span class="bubble__file-body"><span class="bubble__file-name">' + name + '</span><span class="bubble__file-meta">' + size + '</span></span><span class="chat-attachment__download">↓</span></a>';
      } else {
        mediaHtml = '<a class="chat-attachment chat-attachment--image" href="' + safeUrl + '" target="_blank" rel="noopener"><img src="' + safeUrl + '" alt="Ảnh đính kèm" loading="lazy"></a>';
      }
    }

    let visualHtml = '';
    if (isCallMessage) {
      visualHtml = specialCallHtml;
    } else {
      visualHtml = contentHtml + mediaHtml;
    }

    const timestampHtml = '<span class="msg-row__timestamp">' + formatClock(sentAt) + '</span>';
    const payload = document.createElement('div');
    payload.className = 'msg-row__content' + (isCallMessage ? ' msg-row__content--call' : '');
    payload.innerHTML = visualHtml + timestampHtml;

    row.innerHTML = avatarHtml;
    row.appendChild(payload);
    messageList.appendChild(row);

    lastRenderedSenderId = String(msg.senderId);
    lastRenderedAt = sentAtMs;
    lastRenderedBubbleEl = payload;

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


  // ---------- WebRTC video call 1-1 ----------
  const videoCallModal = document.getElementById('videoCallModal');
  const remoteVideo = document.getElementById('remoteVideo');
  const localVideo = document.getElementById('localVideo');
  const videoCallTitle = document.getElementById('videoCallTitle');
  const videoCallStatus = document.getElementById('videoCallStatus');
  const answerCallBtn = document.getElementById('answerCallBtn');
  const hangupCallBtn = document.getElementById('hangupCallBtn');
  const closeVideoCallBtn = document.getElementById('closeVideoCallBtn');
  const toggleMicBtn = document.getElementById('toggleMicBtn');
  const toggleCameraBtn = document.getElementById('toggleCameraBtn');
  const rejectCallBtn = document.getElementById('rejectCallBtn');
  const incomingCallProfile = document.getElementById('incomingCallProfile');
  const incomingCallAvatar = document.getElementById('incomingCallAvatar');
  let ringtoneCtx = null;
  let ringtoneTimer = null;

  function startRingtone() {
    stopRingtone();
    try {
      ringtoneCtx = new (window.AudioContext || window.webkitAudioContext)();
      const beep = () => {
        if (!ringtoneCtx) return;
        const osc = ringtoneCtx.createOscillator();
        const gain = ringtoneCtx.createGain();
        osc.frequency.value = 880; gain.gain.value = 0.035;
        osc.connect(gain); gain.connect(ringtoneCtx.destination);
        osc.start(); osc.stop(ringtoneCtx.currentTime + 0.18);
      };
      beep(); ringtoneTimer = setInterval(beep, 900);
    } catch (_) {}
  }

  function stopRingtone() {
    if (ringtoneTimer) clearInterval(ringtoneTimer);
    ringtoneTimer = null;
    if (ringtoneCtx) { ringtoneCtx.close().catch(() => {}); ringtoneCtx = null; }
  }

  function signalCall(destination, payload) {
    if (!stompClientRef) throw new Error('WebSocket chưa sẵn sàng');
    stompClientRef.send(destination, {}, JSON.stringify(payload));
  }

  function createPeerConnection(targetUserId, callId) {
    if (peerConnection) peerConnection.close();
    peerConnection = new RTCPeerConnection(rtcConfig);
    activeCallId = callId || activeCallId || ('call-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8));
    peerConnection.onicecandidate = e => {
      if (e.candidate) signalCall('/app/call.ice', { targetUserId: Number(targetUserId), callId: activeCallId, candidate: e.candidate });
    };
    peerConnection.ontrack = e => { if (remoteVideo && e.streams[0]) remoteVideo.srcObject = e.streams[0]; };
    peerConnection.onconnectionstatechange = () => {
      if (!peerConnection) return;
      const state = peerConnection.connectionState;
      videoCallStatus.textContent = state === 'connected' ? 'Đang gọi' : ('Trạng thái: ' + state);
      if (state === 'failed' || state === 'closed' || state === 'disconnected') {
        closeVideoCall(false);
      }
    };
    if (localStream) localStream.getTracks().forEach(t => peerConnection.addTrack(t, localStream));
    return peerConnection;
  }

  async function ensureLocalStream(callType) {
    if (localStream) return localStream;
    localStream = await navigator.mediaDevices.getUserMedia({ video: callType !== 'audio', audio: true });
    if (localVideo) localVideo.srcObject = localStream;
    return localStream;
  }

  function clearCallTimers() {
    if (outgoingCallTimer) clearTimeout(outgoingCallTimer);
    if (incomingCallTimer) clearTimeout(incomingCallTimer);
    outgoingCallTimer = null;
    incomingCallTimer = null;
  }

  function configureCallUi(callType, name, avatar, incoming) {
    activeCallType = callType === 'audio' ? 'audio' : 'video';
    const isAudio = activeCallType === 'audio';
    const videoGrid = document.getElementById('videoCallGrid');
    const audioProfile = document.getElementById('audioCallProfile');
    const audioAvatar = document.getElementById('audioCallAvatar');
    const audioName = document.getElementById('audioCallName');
    if (videoGrid) videoGrid.hidden = isAudio;
    if (audioProfile) audioProfile.hidden = !isAudio;
    if (audioAvatar && avatar) audioAvatar.src = avatar;
    if (audioName) audioName.textContent = name || (incoming ? 'Cuộc gọi đến' : 'Cuộc gọi thoại');
    if (videoCallTitle) {
      videoCallTitle.textContent = incoming
        ? ('Cuộc gọi ' + (isAudio ? 'thoại' : 'video') + ' đến · ' + (name || 'Bạn bè'))
        : ('Gọi ' + (isAudio ? 'thoại' : 'video') + ' · ' + (name || 'Bạn bè'));
    }
    if (toggleCameraBtn) toggleCameraBtn.hidden = isAudio;
  }

  async function startCall(targetUserId, targetName, callType) {
    if (!targetUserId || String(targetUserId) === String(currentUserId)) return;
    if (!window.RTCPeerConnection || !navigator.mediaDevices?.getUserMedia) {
      Aloute.toast('Trình duyệt không hỗ trợ gọi', true);
      return;
    }
    try {
      clearCallTimers();
      const callId = 'call-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
      activeCallId = callId;
      callPeerUserId = Number(targetUserId);
      configureCallUi(callType, targetName, null, false);
      videoCallStatus.textContent = 'Đang mở ' + (callType === 'audio' ? 'micro...' : 'camera...');
      answerCallBtn.hidden = true;
      if (rejectCallBtn) rejectCallBtn.hidden = true;
      if (incomingCallProfile) incomingCallProfile.hidden = true;
      videoCallModal.hidden = false;

      await ensureLocalStream(activeCallType);
      createPeerConnection(targetUserId, callId);
      const offer = await peerConnection.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: callType !== 'audio' });
      await peerConnection.setLocalDescription(offer);
      signalCall('/app/call.offer', {
        targetUserId: Number(targetUserId),
        callId,
        callerUserId: Number(currentUserId),
        sdp: offer,
        callType: activeCallType
      });
      videoCallStatus.textContent = 'Đang chờ ' + (targetName || 'người nhận') + ' chấp nhận...';

      // Backend CallTimeoutService là nguồn thời gian chuẩn. Timer này chỉ là fallback
      // để đóng giao diện caller nếu mạng không trả về signal timeout.
      outgoingCallTimer = setTimeout(() => {
        if (activeCallId !== callId) return;
        closeVideoCall(false);
        Aloute.toast('Không có phản hồi sau 30 giây. Cuộc gọi đã kết thúc và được ghi nhận là cuộc gọi nhỡ.');
      }, CALL_TIMEOUT_MS + 500);
    } catch (err) {
      clearCallTimers();
      closeVideoCall(false);
      Aloute.toast('Không thể bắt đầu cuộc gọi: ' + (err.message || 'lỗi camera/micro'), true);
    }
  }

  async function acceptIncomingCall() {
    stopRingtone();
    if (!pendingIncomingOffer) return;
    const offer = pendingIncomingOffer;
    pendingIncomingOffer = null;
    clearCallTimers();
    try {
      videoCallStatus.textContent = 'Đang kết nối...';
      activeCallType = offer.callType === 'audio' ? 'audio' : 'video';
      await ensureLocalStream(activeCallType);
      activeCallId = offer.callId;
      callPeerUserId = Number(offer.fromUserId);
      createPeerConnection(offer.fromUserId, offer.callId);
      await peerConnection.setRemoteDescription(new RTCSessionDescription(offer.sdp));
      const answer = await peerConnection.createAnswer();
      await peerConnection.setLocalDescription(answer);
      signalCall('/app/call.answer', { targetUserId: Number(offer.fromUserId), callId: offer.callId, callerUserId: Number(offer.callerUserId || offer.fromUserId), callType: activeCallType, sdp: answer });
      answerCallBtn.hidden = true;
      videoCallStatus.textContent = 'Đang gọi';
    } catch (err) {
      Aloute.toast('Không thể nhận cuộc gọi: ' + (err.message || ''), true);
      closeVideoCall(false);
    }
  }

  function rejectIncomingCall() {
    if (pendingIncomingOffer) {
      try { signalCall('/app/call.hangup', { targetUserId: Number(pendingIncomingOffer.fromUserId), callId: pendingIncomingOffer.callId, callerUserId: Number(pendingIncomingOffer.callerUserId || pendingIncomingOffer.fromUserId), callType: pendingIncomingOffer.callType || 'video', reason: 'rejected', missed: false }); } catch (_) {}
    }
    stopRingtone();
    pendingIncomingOffer = null;
    closeVideoCall(false);
  }

  async function handleCallSignal(signal) {
    if (!signal || !signal.type) return;
    if (signal.type === 'offer') {
      clearCallTimers();
      pendingIncomingOffer = signal;
      const fromName = signal.fromFullName || signal.fromUsername || 'Bạn bè';
      const fromAvatar = signal.fromAvatarUrl || '/images/avatar_default.png';
      activeCallType = signal.callType === 'audio' ? 'audio' : 'video';
      configureCallUi(activeCallType, fromName, fromAvatar, true);
      videoCallStatus.textContent = 'Cuộc gọi ' + (activeCallType === 'audio' ? 'thoại' : 'video') + ' đến...';
      answerCallBtn.hidden = false;
      if (rejectCallBtn) rejectCallBtn.hidden = false;
      if (incomingCallProfile) incomingCallProfile.hidden = false;
      if (incomingCallAvatar) incomingCallAvatar.src = fromAvatar;
      videoCallModal.hidden = false;
      startRingtone();
      // Không gửi hangup từ phía receiver khi hết giờ. Backend scheduler sẽ xử lý
      // timeout và gửi signal tới cả caller + receiver, đồng thời tạo CALL_MISSED.
      incomingCallTimer = setTimeout(() => {
        if (!pendingIncomingOffer || pendingIncomingOffer.callId !== signal.callId) return;
        pendingIncomingOffer = null;
        closeVideoCall(false);
      }, CALL_TIMEOUT_MS + 500);
      return;
    }
    if (signal.type === 'answer') {
      if (!peerConnection || signal.callId !== activeCallId) return;
      await peerConnection.setRemoteDescription(new RTCSessionDescription(signal.sdp));
      clearCallTimers();
      videoCallStatus.textContent = 'Đang kết nối...';
      return;
    }
    if (signal.type === 'ice') {
      if (!peerConnection || signal.callId !== activeCallId || !signal.candidate) return;
      try { await peerConnection.addIceCandidate(signal.candidate); } catch (_) {}
      return;
    }
    if (signal.type === 'hangup') {
      const reason = signal.reason || '';
      closeVideoCall(false);
      if (reason === 'timeout') {
        Aloute.toast('Cuộc gọi nhỡ: bạn không nhận cuộc gọi trong 30 giây.');
      } else if (reason === 'rejected') {
        Aloute.toast('Cuộc gọi đã bị từ chối.');
      } else if (reason === 'receiver-timeout') {
        Aloute.toast('Không có phản hồi trong 30 giây.');
      } else {
        Aloute.toast('Cuộc gọi đã kết thúc');
      }
    }
  }

  function closeVideoCall(sendHangup) {
    stopRingtone();
    clearCallTimers();
    const peerUserId = callPeerUserId || activeUserId;
    if (sendHangup && peerUserId && activeCallId && stompClientRef) {
      try { signalCall('/app/call.hangup', {
        targetUserId: Number(peerUserId),
        callId: activeCallId,
        callerUserId: Number(currentUserId),
        callType: activeCallType,
        reason: 'cancelled',
        missed: false
      }); } catch (_) {}
    }
    if (peerConnection) { peerConnection.ontrack = null; peerConnection.close(); }
    peerConnection = null;
    if (localStream) { localStream.getTracks().forEach(t => t.stop()); localStream = null; }
    if (localVideo) localVideo.srcObject = null;
    if (remoteVideo) remoteVideo.srcObject = null;
    activeCallId = null;
    callPeerUserId = null;
    pendingIncomingOffer = null;
    if (answerCallBtn) answerCallBtn.hidden = true;
    if (rejectCallBtn) rejectCallBtn.hidden = true;
    if (incomingCallProfile) incomingCallProfile.hidden = true;
    if (videoCallModal) videoCallModal.hidden = true;
  }

  function retryCallFromCard(card) {
    if (!card) return;
    const targetId = Number(card.dataset.callAgain);
    if (!targetId) return;
    const targetName = document.getElementById('chatThreadName')?.textContent || 'Bạn bè';
    startCall(targetId, targetName, card.dataset.callType === 'audio' ? 'audio' : 'video');
  }

  document.addEventListener('click', e => {
    const card = e.target.closest('[data-call-again]');
    if (!card) return;
    retryCallFromCard(card);
  });

  document.addEventListener('keydown', e => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const card = e.target.closest('[data-call-again]');
    if (!card) return;
    e.preventDefault();
    retryCallFromCard(card);
  });

  document.addEventListener('aloute:call-signal', e => { handleCallSignal(e.detail).catch(() => closeVideoCall(false)); });
  if (answerCallBtn) answerCallBtn.addEventListener('click', acceptIncomingCall);
  if (rejectCallBtn) rejectCallBtn.addEventListener('click', rejectIncomingCall);
  if (hangupCallBtn) hangupCallBtn.addEventListener('click', () => closeVideoCall(true));
  if (closeVideoCallBtn) closeVideoCallBtn.addEventListener('click', () => closeVideoCall(true));
  if (videoCallModal) videoCallModal.addEventListener('click', e => { if (e.target === videoCallModal) closeVideoCall(true); });
  if (toggleMicBtn) toggleMicBtn.addEventListener('click', () => {
    if (!localStream) return;
    const track = localStream.getAudioTracks()[0];
    if (!track) return;
    track.enabled = !track.enabled;
    toggleMicBtn.textContent = track.enabled ? '🎙 Tắt mic' : '🔇 Bật mic';
  });
  if (toggleCameraBtn) toggleCameraBtn.addEventListener('click', () => {
    if (!localStream) return;
    const track = localStream.getVideoTracks()[0];
    if (!track) return;
    track.enabled = !track.enabled;
    toggleCameraBtn.textContent = track.enabled ? '📹 Tắt camera' : '🚫 Bật camera';
  });


  // ---------- Chỉ báo "đang nhập" (typing) ----------
  function notifyTyping() {
    if (!stompClientRef || !activeUserId) return;
    const now = Date.now();
    if (now - lastTypingSentAt < 2000) return;
    lastTypingSentAt = now;
    stompClientRef.send('/app/chat.typing', {}, JSON.stringify({ receiverId: Number(activeUserId) }));
  }


  // ---------- Chụp ảnh / quay video ----------
  const mediaCaptureModal = document.getElementById('mediaCaptureModal');
  const mediaCaptureVideo = document.getElementById('mediaCaptureVideo');
  const mediaCaptureCanvas = document.getElementById('mediaCaptureCanvas');
  const capturePhotoBtn = document.getElementById('capturePhotoBtn');
  const recordVideoBtn = document.getElementById('recordVideoBtn');
  const closeMediaCaptureBtn = document.getElementById('closeMediaCaptureBtn');
  const captureSnapshotBtn = document.getElementById('captureSnapshotBtn');
  const startRecordBtn = document.getElementById('startRecordBtn');
  const stopRecordBtn = document.getElementById('stopRecordBtn');
  const mediaCaptureStatus = document.getElementById('mediaCaptureStatus');

  async function openCapture(mode) {
    if (!mediaCaptureModal || !navigator.mediaDevices?.getUserMedia) {
      Aloute.toast('Trình duyệt không hỗ trợ camera/microphone', true);
      return;
    }
    captureMode = mode;
    mediaCaptureStatus.textContent = mode === 'photo' ? 'Đang mở camera...' : 'Đang mở camera để quay video...';
    capturePhotoBtn.hidden = mode !== 'photo';
    startRecordBtn.hidden = mode !== 'video';
    stopRecordBtn.hidden = true;
    try {
      captureStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: mode === 'video' });
      mediaCaptureVideo.srcObject = captureStream;
      mediaCaptureModal.hidden = false;
      mediaCaptureStatus.textContent = mode === 'photo' ? 'Sẵn sàng chụp' : 'Sẵn sàng quay';
    } catch (err) {
      Aloute.toast('Không thể truy cập camera: ' + (err.message || 'từ chối quyền'), true);
    }
  }

  function stopCaptureStream() {
    if (captureStream) captureStream.getTracks().forEach(t => t.stop());
    captureStream = null;
    if (mediaCaptureVideo) mediaCaptureVideo.srcObject = null;
  }

  function closeCapture() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') mediaRecorder.stop();
    stopCaptureStream();
    if (mediaCaptureModal) mediaCaptureModal.hidden = true;
  }

  async function uploadMediaBlob(blob, fileName, mediaType) {
    const form = new FormData();
    form.append('file', blob, fileName);
    const result = await Aloute.api('/api/upload/media', { method: 'POST', body: form });
    return { url: result.url, type: mediaType || result.type, name: result.name || fileName, size: Number(result.size || blob.size || 0) };
  }

  async function sendMediaFile(blob, fileName, mediaType) {
    if (!stompClientRef || (!activeUserId && !activeGroupId)) throw new Error('Chưa chọn cuộc trò chuyện');
    mediaCaptureStatus.textContent = 'Đang tải lên...';
    const media = await uploadMediaBlob(blob, fileName, mediaType);
    const clientId = 'm' + Date.now() + Math.random().toString(36).slice(2, 8);
    const isGroup = !!activeGroupId;
    const msg = {
      senderId: currentUserId, content: '', sentAt: new Date().toISOString(),
      mediaUrl: media.url, mediaType: media.type, mediaName: media.name, mediaSize: media.size, clientId: clientId
    };
    const optimisticRow = appendBubble(msg, { pending: true });
    const timeoutId = setTimeout(() => markPendingFailed(clientId), 20000);
    pendingByClientId.set(clientId, isGroup
      ? { el: optimisticRow, content: '', groupId: Number(activeGroupId), timeoutId }
      : { el: optimisticRow, content: '', receiverId: Number(activeUserId), timeoutId });
    const payload = isGroup
      ? { groupId: Number(activeGroupId), content: '', mediaUrl: media.url, mediaType: media.type, mediaName: media.name, mediaSize: media.size, clientId }
      : { receiverId: Number(activeUserId), content: '', mediaUrl: media.url, mediaType: media.type, mediaName: media.name, mediaSize: media.size, clientId };
    stompClientRef.send('/app/chat.send', {}, JSON.stringify(payload));
  }

  const EMOJIS = ['😀','😂','😍','🥰','😘','😎','🤩','😭','😢','😅','🤣','😉','😊','🙂','🙃','😮','😡','🤔','👍','👏','🔥','❤️','💯','🎉','✨','🙏','💪','🥹','😂','😴','🤝','🚀'];

  function getEmojiPicker() {
    return document.getElementById('emojiPicker');
  }

  function getChatImageInput() {
    return document.getElementById('chatImageInput');
  }

  function renderEmojiPicker() {
    const picker = getEmojiPicker();
    if (!picker || picker.dataset.ready === 'true') return;
    picker.innerHTML = EMOJIS.map(e => '<button type="button" class="emoji-picker__item" data-emoji="' + e + '" aria-label="' + e + '">' + e + '</button>').join('');
    picker.dataset.ready = 'true';
  }

  function toggleEmojiPicker() {
    const picker = getEmojiPicker();
    if (!picker) return;
    renderEmojiPicker();
    picker.hidden = !picker.hidden;
  }

  async function handleImageSelection(file) {
    if (!file) return;
    if (file.size > MAX_UPLOAD_BYTES) {
      Aloute.toast('Tệp không được vượt quá 2GB.', true);
      return;
    }
    if (!file.type || (!file.type.startsWith('image/') && !file.type.startsWith('video/'))) {
      Aloute.toast('Vui lòng chọn ảnh hoặc video.', true);
      return;
    }
    try {
      await sendMediaFile(file, file.name || 'aloute-media', file.type.startsWith('video/') ? 'video' : 'image');
    } catch (err) {
      Aloute.toast(err.message || 'Gửi media thất bại.', true);
    } finally {
      const imageInput = getChatImageInput();
      if (imageInput) imageInput.value = '';
    }
  }

  async function handleFileSelection(file) {
    if (!file) return;
    if (file.size > MAX_UPLOAD_BYTES) {
      Aloute.toast('Tệp không được vượt quá 2GB.', true);
      return;
    }
    try {
      await sendMediaFile(file, file.name || 'aloute-file', 'file');
    } catch (err) {
      Aloute.toast(err.message || 'Gửi tệp thất bại.', true);
    } finally {
      const input = document.getElementById('chatFileInput');
      if (input) input.value = '';
    }
  }

  // Các nút nằm trong <template> nên dùng event delegation để hoạt động sau mỗi lần mở chat.
  document.addEventListener('click', e => {
    if (e.target.closest('#capturePhotoBtn')) openCapture('photo');
    if (e.target.closest('#recordVideoBtn')) openCapture('video');
    if (e.target.closest('#sendImageBtn')) {
      const imageInput = getChatImageInput();
      if (imageInput) imageInput.click();
    }
    if (e.target.closest('#sendFileBtn')) {
      const fileInput = document.getElementById('chatFileInput');
      if (fileInput) fileInput.click();
    }
    const emojiButton = e.target.closest('#emojiPickerBtn');
    const emojiItem = e.target.closest('.emoji-picker__item');
    if (emojiButton) {
      e.stopPropagation();
      toggleEmojiPicker();
      return;
    }
    if (emojiItem) {
      const input = document.getElementById('messageInput');
      if (input) {
        const start = input.selectionStart ?? input.value.length;
        const end = input.selectionEnd ?? input.value.length;
        const emoji = emojiItem.dataset.emoji || '';
        input.value = input.value.slice(0, start) + emoji + input.value.slice(end);
        input.focus();
        input.selectionStart = input.selectionEnd = start + emoji.length;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
        const picker = getEmojiPicker();
      if (picker) picker.hidden = true;
      return;
    }
    const picker = getEmojiPicker();
    if (picker && !picker.hidden && !e.target.closest('#emojiPicker')) {
      picker.hidden = true;
    }
  });

  // #chatImageInput nằm trong template nên đăng ký listener sau khi template được clone.
  document.addEventListener('change', e => {
    if (e.target && e.target.id === 'chatImageInput') {
      handleImageSelection(e.target.files?.[0]);
    }
    if (e.target && e.target.id === 'chatFileInput') {
      handleFileSelection(e.target.files?.[0]);
    }
  });

  if (closeMediaCaptureBtn) closeMediaCaptureBtn.addEventListener('click', closeCapture);
  if (mediaCaptureModal) mediaCaptureModal.addEventListener('click', e => { if (e.target === mediaCaptureModal) closeCapture(); });

  if (captureSnapshotBtn) captureSnapshotBtn.addEventListener('click', async () => {
    if (!captureStream || !mediaCaptureVideo.videoWidth) return;
    mediaCaptureCanvas.width = mediaCaptureVideo.videoWidth;
    mediaCaptureCanvas.height = mediaCaptureVideo.videoHeight;
    mediaCaptureCanvas.getContext('2d').drawImage(mediaCaptureVideo, 0, 0);
    mediaCaptureCanvas.toBlob(async blob => {
      try {
        await sendMediaFile(blob, 'aloute-photo.jpg', 'image');
        closeCapture();
      } catch (err) {
        mediaCaptureStatus.textContent = err.message || 'Tải ảnh thất bại';
      }
    }, 'image/jpeg', 0.88);
  });

  if (startRecordBtn) startRecordBtn.addEventListener('click', () => {
    if (!captureStream) return;
    recordedChunks = [];
    let mime = 'video/webm;codecs=vp9,opus';
    if (!MediaRecorder.isTypeSupported(mime)) mime = 'video/webm';
    mediaRecorder = new MediaRecorder(captureStream, { mimeType: mime });
    mediaRecorder.ondataavailable = e => { if (e.data.size) recordedChunks.push(e.data); };
    mediaRecorder.onstop = async () => {
      const blob = new Blob(recordedChunks, { type: mime });
      try {
        await sendMediaFile(blob, 'aloute-video.webm', 'video');
        closeCapture();
      } catch (err) {
        mediaCaptureStatus.textContent = err.message || 'Tải video thất bại';
      }
    };
    mediaRecorder.start(250);
    startRecordBtn.hidden = true;
    captureSnapshotBtn.hidden = true;
    stopRecordBtn.hidden = false;
    mediaCaptureStatus.textContent = 'Đang quay... bấm Dừng quay khi xong';
  });

  if (stopRecordBtn) stopRecordBtn.addEventListener('click', () => {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stop();
      mediaCaptureStatus.textContent = 'Đang xử lý video...';
    }
  });


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
    const bubble = pending.el.querySelector('.bubble, .chat-attachment, .bubble__call');
    if (bubble) {
      bubble.classList.remove('bubble--pending');
      bubble.classList.add('bubble--failed');
      const timeEl = pending.el.querySelector('.msg-row__timestamp');
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
    const bubble = pending.el.querySelector('.bubble, .chat-attachment, .bubble__call');
    if (bubble) {
      bubble.classList.remove('bubble--pending');
      const timeEl = pending.el.querySelector('.msg-row__timestamp');
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
    item.dataset.lastMessageAt = msg.sentAt || new Date().toISOString();

    let preview = item.querySelector('.chat-item__preview');
    if (!preview || preview.classList.contains('chat-item__preview--new')) {
      const bottom = item.querySelector('.chat-item__bottom');
      if (preview) preview.remove();
      preview = document.createElement('span');
      preview.className = 'chat-item__preview';
      bottom.insertBefore(preview, bottom.firstChild);
    }
    if (String(msg.messageType || '').toUpperCase() === 'CALL') {
      const callText = String(msg.callType || '').toLowerCase() === 'audio' ? 'Cuộc gọi thoại' : 'Cuộc gọi video';
      preview.textContent = String(msg.callStatus || '').toUpperCase() === 'MISSED' ? '☎ ' + callText + ' nhỡ' : '☎ ' + callText;
    } else {
      preview.textContent = msg.mediaType
        ? (String(msg.mediaType).toLowerCase() === 'video' ? '🎥 Video' : String(msg.mediaType).toLowerCase() === 'file' ? '📎 ' + (msg.mediaName || 'Tệp') : '📷 Ảnh')
        : (msg.content || '');
    }

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

    // Sắp xếp lại toàn bộ theo sentAt thực tế của tin nhắn mới nhất.
    sortChatListItems();
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

    item.dataset.lastMessageAt = msg.sentAt || new Date().toISOString();
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

    sortChatListItems();
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

  // ---------- Đồng bộ avatar real-time ----------
  document.addEventListener('aloute:avatar-update', (e) => {
    const p = e.detail || {};
    if (!p.userId || !p.avatarUrl) return;
    const selector = '.chat-item[data-user-id="' + p.userId + '"]';
    chatList.querySelectorAll(selector + ' .chat-item__avatar').forEach(img => { img.src = p.avatarUrl; });
    chatList.querySelectorAll(selector).forEach(item => { item.dataset.avatar = p.avatarUrl; });
    if (String(activeUserId) === String(p.userId)) {
      const headerAvatar = document.getElementById('chatThreadAvatar');
      if (headerAvatar) headerAvatar.src = p.avatarUrl;
      if (chatDetailsAvatar) chatDetailsAvatar.src = p.avatarUrl;
      document.querySelectorAll('#messageList .msg-row__avatar').forEach(img => {
        // avatar trong lịch sử được lấy từ sender; chỉ thay ảnh của user này
        const row = img.closest('.msg-row');
        if (row && row.dataset.senderId === String(p.userId)) img.src = p.avatarUrl;
      });
    }
  });

  // ---------- Online/offline (app.js phát 'aloute:presence' từ /queue/presence) ----------
  document.addEventListener('aloute:presence', (e) => {
    const { userId, online } = e.detail;
    setItemOnline(userId, online);
    if (activeUserId && String(userId) === String(activeUserId)) {
      setThreadPresence(online);
    }
    // Cập nhật lại strip Notes khi trạng thái online thay đổi.
    loadChatNotes();
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
    const avatar = user.avatarUrl || '/images/avatar_default.png';
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