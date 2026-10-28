

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
  const chatDetailsMore = document.getElementById('chatDetailsMore');
  const chatMarkReadBtn = document.getElementById('chatMarkReadBtn');
  const chatReportUserBtn = document.getElementById('chatReportUserBtn');
  const chatBlockUserBtn = document.getElementById('chatBlockUserBtn');
  const chatUtilityModal = document.getElementById('chatUtilityModal');
  const chatUtilityTitle = document.getElementById('chatUtilityTitle');
  const chatUtilityContent = document.getElementById('chatUtilityContent');
  const closeChatUtilityBtn = document.getElementById('closeChatUtilityBtn');
  if (!chatList || !chatThread || !template) return;
  if (chatUtilityModal && chatUtilityModal.parentElement !== document.body) document.body.appendChild(chatUtilityModal);

  const currentUserId = document.body.dataset.currentUserId;
  const receiptCache = new Map();

  function receiptRank(label) {
    if (label === 'Đã xem') return 3;
    if (label === 'Đã nhận') return 2;
    if (label === 'Đã gửi') return 1;
    return 0;
  }

  function cacheReceipt(messageId, label) {
    const id = String(messageId || '');
    if (!id) return label;
    const current = receiptCache.get(id);
    if (!current || receiptRank(label) >= receiptRank(current)) receiptCache.set(id, label);
    while (receiptCache.size > 2000) receiptCache.delete(receiptCache.keys().next().value);
    return receiptCache.get(id) || label;
  }

  function messageReceiptLabel(message) {
    if (!message) return 'Đã gửi';
    if (message.read === true || Number(message.readCount || 0) > 0) return 'Đã xem';
    if (message.delivered === true || Number(message.deliveredCount || 0) > 0) return 'Đã nhận';
    return message.id ? 'Đã gửi' : 'Đang gửi…';
  }

  function markReadAndSync(path) {
    return Aloute.api(path, { method: 'POST' }).then(value => {
      if (typeof Aloute.refreshNavBadges === 'function') Aloute.refreshNavBadges(true);
      return value;
    }).catch(() => {});
  }

  function updateReceiptForMessageIds(ids, label) {
    const messageIds = new Set((Array.isArray(ids) ? ids : [ids]).filter(id => id != null).map(String));
    if (!messageIds.size) return;
    messageIds.forEach(id => cacheReceipt(id, label));
    document.querySelectorAll('#messageList .msg-row[data-message-id]').forEach(row => {
      if (!messageIds.has(String(row.dataset.messageId))) return;
      if (String(row.dataset.senderId) !== String(currentUserId)) return;
      let receipt = row.querySelector('.msg-row__receipt');
      if (!receipt) {
        receipt = document.createElement('small');
        receipt.className = 'msg-row__receipt';
        row.querySelector('.msg-row__content')?.appendChild(receipt);
      }
      if (receipt) receipt.textContent = cacheReceipt(row.dataset.messageId, label);
    });
  }

  let activeUserId = null;
  let activeGroupId = null;
  let activeItemEl = null;
  let stompClientRef = null;

  const pendingByClientId = new Map();

  let lastRenderedSenderId = null;
  let lastRenderedAt = 0;
  let lastRenderedBubbleEl = null;
  let lastRenderedDateKey = null;
  let lastMineBubbleEl = null;

  let typingHideTimer = null;
  let lastTypingSentAt = 0;

  let captureStream = null;
  let mediaRecorder = null;
  let recordedChunks = [];
  let captureMode = null;

  const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
  const MAX_FILE_UPLOAD_BYTES = 1024 * 1024;

  Aloute.onRealtimeReady(function (client) { stompClientRef = client; });

  function openChatUtility(title) {
    if (!chatUtilityModal || !chatUtilityContent) return false;
    chatUtilityTitle.textContent = title;
    chatUtilityContent.replaceChildren();
    if (!chatUtilityModal.open) chatUtilityModal.showModal();
    document.body.style.overflow = 'hidden';
    return true;
  }

  function closeChatUtility() {
    if (chatUtilityModal) {
      if (chatUtilityModal.open) chatUtilityModal.close();
      document.body.style.overflow = '';
    }
  }

  if (closeChatUtilityBtn) closeChatUtilityBtn.addEventListener('click', closeChatUtility);
  if (chatUtilityModal) {
    chatUtilityModal.addEventListener('click', event => { if (event.target === chatUtilityModal) closeChatUtility(); });
    chatUtilityModal.addEventListener('close', () => { document.body.style.overflow = ''; });
  }

  if (chatDetailsSearchBtn) chatDetailsSearchBtn.addEventListener('click', () => {
    if (!openChatUtility('Tìm kiếm trong đoạn chat')) return;
    const input = document.createElement('input');
    input.className = 'chat-utility-search'; input.type = 'search'; input.placeholder = 'Nhập nội dung cần tìm…';
    const results = document.createElement('div'); results.className = 'chat-utility-results';
    const search = () => {
      const query = input.value.trim().toLocaleLowerCase('vi'); results.replaceChildren();
      if (!query) { results.innerHTML = '<p class="chat-utility-empty">Nhập từ khóa để tìm trong các tin nhắn đã tải.</p>'; return; }
      const matches = Array.from(document.querySelectorAll('#messageList .msg-row')).filter(row =>
        (row.querySelector('.bubble__text')?.textContent || '').toLocaleLowerCase('vi').includes(query));
      if (!matches.length) { results.innerHTML = '<p class="chat-utility-empty">Không tìm thấy tin nhắn phù hợp.</p>'; return; }
      matches.forEach(row => {
        const item = document.createElement('button'); item.type = 'button'; item.className = 'chat-utility-result';
        item.textContent = row.querySelector('.bubble__text')?.textContent || '';
        item.addEventListener('click', () => { closeChatUtility(); row.scrollIntoView({ behavior:'smooth', block:'center' }); row.classList.add('is-search-match'); setTimeout(() => row.classList.remove('is-search-match'), 1800); });
        results.append(item);
      });
    };
    input.addEventListener('input', search); chatUtilityContent.append(input, results); search(); input.focus();
  });

  if (chatDetailsMediaBtn) chatDetailsMediaBtn.addEventListener('click', () => {
    if (!openChatUtility('Ảnh và video đã gửi')) return;
    const media = Array.from(document.querySelectorAll('#messageList .chat-attachment--image, #messageList .chat-attachment--media'));
    if (!media.length) { chatUtilityContent.innerHTML = '<p class="chat-utility-empty">Chưa có ảnh hoặc video trong đoạn chat này.</p>'; return; }
    const grid = document.createElement('div'); grid.className = 'chat-utility-media';
    media.forEach(source => { const copy = source.cloneNode(true); copy.querySelector('video')?.removeAttribute('controls'); grid.append(copy); });
    chatUtilityContent.append(grid);
  });

  async function markConversationRead() {
      if (activeGroupId) await markReadAndSync('/api/chat/group/' + encodeURIComponent(activeGroupId) + '/read');
      else if (activeUserId) await markReadAndSync('/api/chat/read/' + encodeURIComponent(activeUserId));
      activeItemEl?.querySelector('.chat-item__unread')?.remove();
      if (chatDetailsMore) chatDetailsMore.open = false;
      Aloute.toast('Đã đánh dấu cuộc trò chuyện là đã đọc');
  }

  async function runChatDetailAction(button, action) {
    button.disabled = true;
    try {
      await action();
    } catch (error) {
      Aloute.toast(error.message, true);
    } finally {
      button.disabled = false;
    }
  }

  if (chatMarkReadBtn) chatMarkReadBtn.addEventListener('click', () => runChatDetailAction(chatMarkReadBtn, markConversationRead));
  if (chatReportUserBtn) chatReportUserBtn.addEventListener('click', () => runChatDetailAction(chatReportUserBtn, async () => {
    if (!activeUserId) throw new Error('Hãy chọn một cuộc trò chuyện cá nhân');
    const reason = await AlouteUI.prompt({ title: 'Báo cáo tài khoản', label: 'Nhập lý do báo cáo tài khoản này', okText: 'Gửi báo cáo' });
    if (!reason?.trim()) return;
    await Aloute.api('/api/reports', { method:'POST', body:{ targetType:'USER', targetId:String(activeUserId), reason:reason.trim() } });
    if (chatDetailsMore) chatDetailsMore.open = false;
    Aloute.toast('Đã gửi báo cáo');
  }));
  if (chatBlockUserBtn) chatBlockUserBtn.addEventListener('click', () => runChatDetailAction(chatBlockUserBtn, async () => {
    if (!activeUserId) throw new Error('Hãy chọn một cuộc trò chuyện cá nhân');
    if (!(await AlouteUI.confirm({ title: 'Chặn người dùng', message: 'Chặn người dùng này? Hai bạn sẽ không thể xem hồ sơ hoặc nhắn tin cho nhau.', okText: 'Chặn' }))) return;
    await Aloute.api('/api/blocks/' + encodeURIComponent(activeUserId), { method:'PUT' });
    window.location.href = '/chat';
  }));

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
        .filter(x => x.online && x.friend)
        .slice(0, 20);

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
        item.title = friend.fullName || friend.username || '';

        const avatar = document.createElement('span');
        avatar.className = 'chat-note__avatar-wrap';
        avatar.innerHTML = '<img class="chat-note__avatar" alt="">' + '<span class="chat-note__online-dot"></span>';
        const img = avatar.querySelector('img');
        img.src = friend.avatarUrl || '/images/avatar_default.png';

        const name = document.createElement('span');
        name.className = 'chat-note__name';
        name.textContent = friend.fullName || friend.username || 'Bạn bè';

        item.appendChild(avatar);
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

      chatNotes.hidden = true;
    }
  }

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

  function createConversationItem(conversation) {
    if (!conversation) return null;
    const isGroup = conversation.group === true;
    const identity = isGroup ? conversation.groupId : conversation.otherUserId;
    if (identity == null) return null;
    const selector = isGroup
      ? '.chat-item[data-group-id="' + String(identity) + '"]'
      : '.chat-item[data-user-id="' + String(identity) + '"]';
    if (chatListScroll.querySelector(selector)) return null;

    const item = document.createElement('div');
    item.className = 'chat-item' + (!isGroup && conversation.hasConversation === false ? ' chat-item--preview' : '');
    if (isGroup) {
      item.dataset.groupId = String(identity);
      item.dataset.groupName = conversation.groupName || '';
      item.dataset.memberCount = String(conversation.memberCount || 0);
    } else {
      item.dataset.userId = String(identity);
      item.dataset.username = conversation.otherUsername || '';
      item.dataset.fullname = conversation.otherFullName || conversation.otherUsername || '';
      item.dataset.online = conversation.online === true ? 'true' : 'false';
      item.dataset.hasConversation = conversation.hasConversation === false ? 'false' : 'true';
    }
    item.dataset.avatar = conversation.otherAvatarUrl || '';
    item.dataset.lastMessageAt = conversation.lastMessageAt || '';

    const avatarWrap = document.createElement('span');
    avatarWrap.className = 'chat-item__avatar-wrap';
    const avatar = document.createElement('img');
    avatar.className = 'chat-item__avatar' + (isGroup ? ' chat-item__avatar--group' : '');
    avatar.alt = isGroup ? 'avatar nhóm' : 'avatar';
    avatar.src = conversation.otherAvatarUrl
      || (isGroup ? 'https://api.dicebear.com/7.x/identicon/svg?seed=' + encodeURIComponent(identity) : '/images/avatar_default.png');
    avatarWrap.appendChild(avatar);
    if (!isGroup && conversation.online === true) {
      const dot = document.createElement('span'); dot.className = 'presence-dot'; avatarWrap.appendChild(dot);
    }

    const body = document.createElement('div'); body.className = 'chat-item__body';
    const top = document.createElement('div'); top.className = 'chat-item__top';
    const name = document.createElement('span'); name.className = 'chat-item__name';
    name.textContent = isGroup ? (conversation.groupName || 'Nhóm') : (conversation.otherFullName || conversation.otherUsername || 'Người dùng');
    const time = document.createElement('span'); time.className = 'chat-item__time';
    time.textContent = conversation.lastMessageTimeAgo || '';
    top.append(name, time);
    const bottom = document.createElement('div'); bottom.className = 'chat-item__bottom';
    const preview = document.createElement('span'); preview.className = 'chat-item__preview';
    preview.textContent = !isGroup && conversation.hasConversation === false
      ? 'Bạn bè mới · Bắt đầu trò chuyện' : (conversation.lastMessage || '');
    bottom.appendChild(preview);
    const unread = Number(conversation.unreadCount || 0);
    if (unread > 0) {
      const badge = document.createElement('span'); badge.className = 'chat-item__unread';
      badge.textContent = unread > 99 ? '99+' : String(unread); bottom.appendChild(badge);
    }
    body.append(top, bottom); item.append(avatarWrap, body);
    item.addEventListener('click', () => openConversation(item));
    return item;
  }

  let loadingConversationPage = false;
  async function loadMoreConversations() {
    if (!chatListScroll || loadingConversationPage || chatListScroll.dataset.hasMore !== 'true') return;
    const cursor = chatListScroll.dataset.nextCursor || '';
    if (!cursor) return;
    loadingConversationPage = true;
    try {
      const page = await Aloute.api('/api/chat/conversations/cursor?size=30&cursor=' + encodeURIComponent(cursor));
      (page && Array.isArray(page.items) ? page.items : []).forEach(conversation => {
        const item = createConversationItem(conversation);
        if (item) chatListScroll.appendChild(item);
      });
      chatListScroll.dataset.nextCursor = page && page.nextCursor ? page.nextCursor : '';
      chatListScroll.dataset.hasMore = page && page.hasMore === true ? 'true' : 'false';
      sortChatListItems();
      if (chatListScroll.dataset.hasMore === 'true'
          && chatListScroll.scrollHeight <= chatListScroll.clientHeight + 40) {
        setTimeout(loadMoreConversations, 0);
      }
    } catch (error) {
      console.warn('Could not load the next conversation page.', error);
    } finally {
      loadingConversationPage = false;
    }
  }

  if (chatListScroll) chatListScroll.addEventListener('scroll', () => {
    if (chatListScroll.scrollTop + chatListScroll.clientHeight >= chatListScroll.scrollHeight - 160) {
      loadMoreConversations();
    }
  });

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

  chatList.querySelectorAll('.chat-item[data-user-id], .chat-item[data-group-id]').forEach(item => {
    item.addEventListener('click', () => openConversation(item));
  });

  sortChatListItems();
  loadChatNotes();
  if (chatListScroll && chatListScroll.dataset.hasMore === 'true') {
    setTimeout(() => {
      if (chatListScroll.scrollHeight <= chatListScroll.clientHeight + 40) loadMoreConversations();
    }, 0);
  }

  if (chatShell && chatShell.dataset.openUserId) {
    const targetItem = chatList.querySelector('.chat-item[data-user-id="' + chatShell.dataset.openUserId + '"]');
    if (targetItem) {
      openConversation(targetItem);
      targetItem.scrollIntoView({ block: 'nearest' });
    }
  } else if (chatShell && chatShell.dataset.openGroupId) {
    const targetItem = chatList.querySelector('.chat-item[data-group-id="' + chatShell.dataset.openGroupId + '"]');
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
    document.body.dataset.activeChatType = 'friend';
    document.body.dataset.activeChatId = String(userId);

    resetClusterTracking();

    const unreadEl = item.querySelector('.chat-item__unread');
    if (unreadEl) unreadEl.remove();

    renderThreadShell(fullName, avatar, online);
    updateChatDetails({
      name: fullName,
      avatar: avatar,
      online: online,
      profileUrl: item.dataset.username ? '/people/' + encodeURIComponent(item.dataset.username) : '/profile/' + encodeURIComponent(userId),
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
    const detailsBtn = document.getElementById('groupMenuBtn');
    if (detailsBtn) {
      detailsBtn.hidden = false;
      detailsBtn.onclick = () => {
        chatDetails.hidden = !chatDetails.hidden;
        detailsBtn.classList.toggle('is-active', !chatDetails.hidden);
      };
    }

    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '<div class="empty-state empty-state--thread"><span>Đang tải tin nhắn...</span></div>';

    try {
      const messages = await Aloute.api('/api/chat/messages/' + userId);
      if (requestVersion !== historyRequestVersion) return;
      renderMessages(messages);
      markReadAndSync('/api/chat/read/' + userId);
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
    document.body.dataset.activeChatType = 'group';
    document.body.dataset.activeChatId = String(groupId);

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
      markReadAndSync('/api/chat/group/' + groupId + '/read');
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

  function linkifyMessageText(value) {
    const text = String(value || '');
    const urlPattern = /https?:\/\/[^\s<>"']+/gi;
    let html = '';
    let cursor = 0;
    let match;
    while ((match = urlPattern.exec(text)) !== null) {
      html += Aloute.escapeHtml(text.slice(cursor, match.index));
      let rawUrl = match[0];
      let suffix = '';
      while (/[.,!?;:)]$/.test(rawUrl)) {
        suffix = rawUrl.slice(-1) + suffix;
        rawUrl = rawUrl.slice(0, -1);
      }
      try {
        const parsed = new URL(rawUrl);
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
          const safeUrl = Aloute.escapeHtml(parsed.href);
          html += '<a class="bubble__link" href="' + safeUrl + '" target="_blank" rel="noopener noreferrer">'
            + Aloute.escapeHtml(rawUrl) + '</a>' + Aloute.escapeHtml(suffix);
        } else {
          html += Aloute.escapeHtml(match[0]);
        }
      } catch (_) {
        html += Aloute.escapeHtml(match[0]);
      }
      cursor = match.index + match[0].length;
    }
    html += Aloute.escapeHtml(text.slice(cursor));
    return html;
  }

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

  const formatCallDuration = seconds => window.AlouteCalls.formatDuration(seconds);

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
        '<span class="bubble__text">' + linkifyMessageText(msg.content || '') + '</span>' +
      '</div>';
    } else if (senderNameHtml) {

      contentHtml += '<div class="msg-row__sender-name">' + senderNameHtml + '</div>';
    }

    const specialCallHtml = isCallMessage
      ? window.AlouteCalls.renderMessage(msg, mine, formatClock(sentAt)) : '';

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
    if (mine) {
      const receipt = document.createElement('small');
      receipt.className = 'msg-row__receipt';
      receipt.textContent = opts.pending ? 'Đang gửi…' : cacheReceipt(msg.id, messageReceiptLabel(msg));
      payload.appendChild(receipt);
    }

    if (msg.id) {
      const menu = document.createElement('details');
      menu.className = 'msg-actions';
      menu.innerHTML = '<summary aria-label="Tùy chọn tin nhắn" title="Tùy chọn">•••</summary>' +
        '<div class="msg-actions__menu"><button type="button" data-delete-mode="self">Xóa ở phía tôi</button>' +
        (mine ? '<button type="button" class="is-danger" data-delete-mode="everyone">Thu hồi với mọi người</button>' : '') + '</div>';
      payload.appendChild(menu);
    }

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

  document.addEventListener('click', async event => {
    const button = event.target.closest('.msg-actions button[data-delete-mode]');
    if (!button) return;
    const row = button.closest('.msg-row');
    const messageId = row?.dataset.messageId;
    if (!messageId) return;
    const forEveryone = button.dataset.deleteMode === 'everyone';
    if (forEveryone && !(await AlouteUI.confirm({ title: 'Thu hồi tin nhắn', message: 'Thu hồi tin nhắn này với mọi người?', okText: 'Thu hồi' }))) return;
    button.disabled = true;
    try {
      await Aloute.api('/api/chat/messages/' + encodeURIComponent(messageId) + '?forEveryone=' + forEveryone, { method:'DELETE' });
      row.remove();
      Aloute.toast(forEveryone ? 'Đã thu hồi tin nhắn' : 'Đã xóa tin nhắn ở phía bạn');
    } catch (error) {
      Aloute.toast(error.message, true);
      button.disabled = false;
    }
  });

  document.addEventListener('aloute:message-deleted', event => {
    const messageId = event.detail?.messageId;
    if (!messageId) return;
    document.querySelector('.msg-row[data-message-id="' + CSS.escape(String(messageId)) + '"]')?.remove();
  });

  function updateLastMineReadState(messages) {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (String(messages[i].senderId) === String(currentUserId)) {
        setReadReceiptText(cacheReceipt(messages[i].id, messageReceiptLabel(messages[i])));
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

  const startCall = (...args) => window.AlouteCalls.start(...args);

  function notifyTyping() {
    if (!stompClientRef || !activeUserId) return;
    const now = Date.now();
    if (now - lastTypingSentAt < 2000) return;
    lastTypingSentAt = now;
    stompClientRef.send('/app/chat.typing', {}, JSON.stringify({ receiverId: Number(activeUserId) }));
  }

  const mediaCaptureModal = document.getElementById('mediaCaptureModal');
  const mediaCaptureVideo = document.getElementById('mediaCaptureVideo');
  const mediaCaptureCanvas = document.getElementById('mediaCaptureCanvas');
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
    captureSnapshotBtn.hidden = mode !== 'photo';
    startRecordBtn.hidden = mode !== 'video';
    stopRecordBtn.hidden = true;
    mediaCaptureModal.hidden = false;
    try {
      captureStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: mode === 'video' });
      mediaCaptureVideo.srcObject = captureStream;
      await mediaCaptureVideo.play();
      mediaCaptureStatus.textContent = mode === 'photo' ? 'Sẵn sàng chụp' : 'Sẵn sàng quay';
    } catch (err) {
      mediaCaptureModal.hidden = true;
      const denied = err && (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError');
      Aloute.toast(denied ? 'Bạn cần cho phép localhost sử dụng camera và microphone' : 'Không thể mở camera: ' + (err.message || 'thiết bị không khả dụng'), true);
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
    form.append('purpose', 'chat');
    const endpoint = mediaType === 'file' ? '/api/upload/file' : '/api/upload/media';
    const result = await Aloute.api(endpoint, { method: 'POST', body: form });
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
      Aloute.toast('Tệp không được vượt quá 50MB.', true);
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
    if (file.size > MAX_FILE_UPLOAD_BYTES) {
      Aloute.toast('Tệp không được vượt quá 1MB.', true);
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
      const receipt = pending.el.querySelector('.msg-row__receipt');
      if (receipt) receipt.textContent = 'Chưa gửi';
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
    const receipt = pending.el.querySelector('.msg-row__receipt');
    if (receipt) receipt.textContent = cacheReceipt(msg.id, messageReceiptLabel(msg));
    return true;
  }

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
      const callStatus = String(msg.callStatus || '').toUpperCase();
      if (callStatus === 'MISSED') preview.textContent = '☎ ' + callText + ' nhỡ';
      else if (callStatus === 'ENDED') preview.textContent = '☎ ' + callText + (msg.callDurationSeconds == null ? '' : ' · ' + formatCallDuration(msg.callDurationSeconds));
      else if (callStatus === 'DECLINED') preview.textContent = '☎ ' + callText + ' bị từ chối';
      else if (callStatus === 'CANCELLED') preview.textContent = '☎ ' + callText + ' đã hủy';
      else preview.textContent = '☎ ' + callText;
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
        markReadAndSync('/api/chat/read/' + otherId);
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
        markReadAndSync('/api/chat/group/' + msg.groupId + '/read');
      }
    }

    upsertGroupListItem(msg);
  }

  document.addEventListener('aloute:typing', (e) => {
    const senderId = String(e.detail.senderId);
    if (activeUserId && senderId === String(activeUserId)) {
      setTypingStatus(true);
    }
  });

  document.addEventListener('aloute:read', (e) => {
    const payload = e.detail || {};
    const readerId = String(payload.readerId || '');
    if (Array.isArray(payload.messageIds) && payload.messageIds.length) {
      updateReceiptForMessageIds(payload.messageIds, 'Đã xem');
    } else if (activeUserId && readerId === String(activeUserId)) {
      setReadReceiptText('Đã xem');
    } else if (activeGroupId && String(payload.groupId || '') === String(activeGroupId)) {
      setReadReceiptText('Đã xem');
    }
    if (typeof Aloute.refreshNavBadges === 'function') Aloute.refreshNavBadges(true);
  });

  document.addEventListener('aloute:delivered', (e) => {
    const payload = e.detail || {};
    if (payload.messageId) updateReceiptForMessageIds([payload.messageId], 'Đã nhận');
  });

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

        const row = img.closest('.msg-row');
        if (row && row.dataset.senderId === String(p.userId)) img.src = p.avatarUrl;
      });
    }
  });

  document.addEventListener('aloute:presence', (e) => {
    const { userId, online } = e.detail;
    setItemOnline(userId, online);
    if (activeUserId && String(userId) === String(activeUserId)) {
      setThreadPresence(online);
    }

    loadChatNotes();
  });

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

  let myFriendsCache = null;

  async function getMyFriends() {
    if (myFriendsCache) return myFriendsCache;
    myFriendsCache = await Aloute.api('/api/users/' + currentUserId + '/friends');
    return myFriendsCache;
  }

  function openModal(modal) { if (modal) modal.hidden = false; }
  function closeModal(modal) { if (modal) modal.hidden = true; }

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
          if (!(await AlouteUI.confirm({ title: 'Xóa thành viên', message: 'Xóa thành viên này khỏi nhóm?', okText: 'Xóa' }))) return;
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
      if (!(await AlouteUI.confirm({ title: 'Rời nhóm', message: 'Rời khỏi nhóm này?', okText: 'Rời nhóm' }))) return;
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

  document.addEventListener('aloute:group-update', (e) => {
    const { groupId, action, affectedUserId } = e.detail;
    const isAboutMe = String(affectedUserId) === String(currentUserId);
    const item = chatList.querySelector('.chat-item[data-group-id="' + groupId + '"]');

    if (action === 'created' || ((action === 'member_removed' || action === 'member_left') && isAboutMe)) {
      location.reload();
      return;
    }

    if (!item) return;

    if (groupInfoModal && !groupInfoModal.hidden && String(groupInfoModal.dataset.groupId) === String(groupId)) {
      refreshGroupInfoModal(groupId);
    }

    if (action === 'member_added' || action === 'member_removed' || action === 'member_left') {
      Aloute.api('/api/chat/groups/' + groupId).then(group => {
        updateGroupListItemMemberCount(groupId, group.members.length);
      }).catch(() => {});
    }
  });
})();
