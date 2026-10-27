(function () {
  'use strict';

  const openWindows = new Map();
  const receiptCache = new Map();
  const MAX_DESKTOP_WINDOWS = 3;
  const DEFAULT_AVATAR = '/images/avatar_default.png';

  // Portal the floating chat dock directly under <body>. A transformed or
  // overflowing ancestor in the feed layout can otherwise clip position:fixed
  // descendants, making a successful click look like it did nothing.
  function currentDock() {
    const dock = document.getElementById('friendsChatDock');
    if (dock && dock.parentElement !== document.body) document.body.appendChild(dock);
    return dock;
  }

  function initializePanel() {
    const panel = document.getElementById('friendsPanel');
    const groupButtons = document.querySelectorAll('[data-group-chat-open]');
    if (!panel && !groupButtons.length) return;

    const dock = currentDock();
    if (dock) dock.dataset.ready = 'true';

    if (panel) {
      panel.querySelectorAll('[data-friend-chat-open]').forEach(button => {
        if (button.dataset.chatClickBound === 'true') return;
        button.dataset.chatClickBound = 'true';
        button.addEventListener('click', event => {
          event.preventDefault();
          event.stopPropagation();
          handleFriendOpen(button);
        });
      });
      panel.dataset.chatPanelReady = 'true';
      updateOnlineSummary();
    }

    groupButtons.forEach(button => {
      if (button.dataset.groupChatClickBound === 'true') return;
      button.dataset.groupChatClickBound = 'true';
      button.addEventListener('click', event => {
        event.preventDefault();
        event.stopPropagation();
        handleGroupOpen(button);
      });
    });
  }

  function handleFriendOpen(button) {
    openFriendChat(button).catch(error => {
      console.error('[ALOUTE] Không mở được cửa sổ chat bạn bè:', error);
      if (window.Aloute && typeof window.Aloute.toast === 'function') {
        window.Aloute.toast(error && error.message ? error.message : 'Không mở được cửa sổ chat. Hãy thử lại.', true);
      }
      const friendId = String(button.dataset.userId || '');
      const windowExists = Array.from(document.querySelectorAll('.friends-chat-window'))
        .some(chatWindow => String(chatWindow.dataset.friendId) === friendId);
      if (friendId && !windowExists) window.location.href = '/chat?userId=' + encodeURIComponent(friendId);
    });
  }

  function handleGroupOpen(button) {
    openGroupChat(button).catch(error => {
      console.error('[ALOUTE] Không mở được cửa sổ chat nhóm:', error);
      if (window.Aloute && typeof window.Aloute.toast === 'function') {
        window.Aloute.toast(error && error.message ? error.message : 'Không mở được cửa sổ chat nhóm. Hãy thử lại.', true);
      }
      const groupId = String(button.dataset.groupId || '');
      const windowExists = Array.from(document.querySelectorAll('.friends-chat-window'))
        .some(chatWindow => String(chatWindow.dataset.groupId) === groupId);
      if (groupId && !windowExists) window.location.href = '/chat?groupId=' + encodeURIComponent(groupId);
    });
  }

  function conversationKey(type, id) {
    return (type === 'group' ? 'group:' : 'user:') + String(id);
  }

  function updateOnlineSummary() {
    const panel = document.getElementById('friendsPanel');
    const summary = document.getElementById('friendsOnlineSummary');
    if (!panel || !summary) return;
    const onlineCount = Array.from(panel.querySelectorAll('.friends-panel__friend'))
      .filter(button => button.dataset.online === 'true').length;
    summary.textContent = onlineCount + ' đang hoạt động';
  }

  function setFriendPresence(userId, online) {
    const panel = document.getElementById('friendsPanel');
    if (!panel) return;
    const button = Array.from(panel.querySelectorAll('.friends-panel__friend'))
      .find(item => String(item.dataset.userId) === String(userId));
    if (button) {
      button.dataset.online = online ? 'true' : 'false';
      const avatar = button.querySelector('.friends-panel__avatar-wrap');
      let dot = avatar && avatar.querySelector('.friends-panel__online-dot');
      if (online && avatar && !dot) {
        dot = document.createElement('span');
        dot.className = 'friends-panel__online-dot';
        dot.setAttribute('aria-label', 'Đang hoạt động');
        avatar.appendChild(dot);
      } else if (!online && dot) {
        dot.remove();
      }
      const status = button.querySelector('.friends-panel__status');
      if (status) status.textContent = online ? 'Đang hoạt động' : 'Nhắn tin';
      button.classList.toggle('is-online', !!online);
      const list = button.parentElement;
      if (list) {
        const rows = Array.from(list.querySelectorAll('.friends-panel__friend'));
        rows.sort((a, b) => {
          const onlineDiff = Number(b.dataset.online === 'true') - Number(a.dataset.online === 'true');
          if (onlineDiff) return onlineDiff;
          return String(a.dataset.fullname || a.dataset.username || '').localeCompare(
            String(b.dataset.fullname || b.dataset.username || ''), 'vi', { sensitivity: 'base' });
        });
        const noResults = list.querySelector('.friends-panel__no-results');
        const fragment = document.createDocumentFragment();
        rows.forEach(row => fragment.appendChild(row));
        list.insertBefore(fragment, noResults);
      }
    }
    updateOnlineSummary();
    openWindows.forEach(win => {
      const state = win && win._alouteChatState;
      if (!win.isConnected || !state || state.type !== 'friend' || String(state.id) !== String(userId)) return;
      win.dataset.online = online ? 'true' : 'false';
      const dot = win.querySelector('.friends-chat-window__online-dot');
      if (dot) dot.hidden = !online;
      const status = win.querySelector('.friends-chat-window__status');
      if (status) status.textContent = online ? 'Đang hoạt động' : 'ALOUTE';
    });
  }

  function applyFriendSearch() {
    const panel = document.getElementById('friendsPanel');
    const input = document.getElementById('friendsPanelSearch');
    const noResults = document.getElementById('friendsPanelNoResults');
    if (!panel || !input) return;
    const query = input.value.trim().toLocaleLowerCase('vi');
    let visible = 0;
    panel.querySelectorAll('.friends-panel__friend').forEach(button => {
      const haystack = [button.dataset.fullname, button.dataset.username].join(' ').toLocaleLowerCase('vi');
      const matches = !query || haystack.includes(query);
      button.hidden = !matches;
      if (matches) visible += 1;
    });
    if (noResults) noResults.hidden = visible !== 0 || panel.querySelectorAll('.friends-panel__friend').length === 0;
  }

  function createWindow(conversation, dock) {
    const win = document.createElement('section');
    win.className = 'friends-chat-window';
    win.dataset.conversationType = conversation.type;
    if (conversation.type === 'group') win.dataset.groupId = String(conversation.id);
    else win.dataset.friendId = String(conversation.id);
    win.dataset.online = conversation.online ? 'true' : 'false';
    win.setAttribute('role', 'dialog');
    win.setAttribute('aria-label', (conversation.type === 'group' ? 'Trò chuyện nhóm ' : 'Trò chuyện với ') + conversation.name);

    const header = document.createElement('header');
    header.className = 'friends-chat-window__header';
    const identity = document.createElement('div');
    identity.className = 'friends-chat-window__identity';
    const avatarWrap = document.createElement('span');
    avatarWrap.className = 'friends-chat-window__avatar-wrap';
    if (conversation.type === 'group') avatarWrap.classList.add('is-group');
    const avatar = document.createElement('img');
    avatar.className = 'friends-chat-window__avatar';
    avatar.src = conversation.avatar || DEFAULT_AVATAR;
    avatar.alt = '';
    avatar.onerror = () => { avatar.onerror = null; avatar.src = DEFAULT_AVATAR; };
    const onlineDot = document.createElement('span');
    onlineDot.className = 'friends-chat-window__online-dot';
    onlineDot.hidden = conversation.type === 'group' || !conversation.online;
    avatarWrap.append(avatar, onlineDot);
    const nameWrap = document.createElement('span');
    nameWrap.className = 'friends-chat-window__name-wrap';
    const name = document.createElement('strong');
    name.textContent = conversation.name;
    const status = document.createElement('small');
    status.className = 'friends-chat-window__status';
    status.textContent = conversation.type === 'group' ? 'Trò chuyện nhóm' : (conversation.online ? 'Đang hoạt động' : 'ALOUTE');
    nameWrap.append(name, status);
    identity.append(avatarWrap, nameWrap);

    const actions = document.createElement('div');
    actions.className = 'friends-chat-window__header-actions';
    const profile = document.createElement('a');
    profile.href = conversation.type === 'group'
      ? '/chat?groupId=' + encodeURIComponent(conversation.id)
      : Aloute.profileUrl(conversation.username, conversation.id);
    profile.className = 'friends-chat-window__header-btn';
    profile.title = conversation.type === 'group' ? 'Mở nhóm trong trang Tin nhắn' : 'Xem trang cá nhân';
    profile.setAttribute('aria-label', profile.title);
    profile.innerHTML = conversation.type === 'group'
      ? '<i class="bi bi-people" aria-hidden="true"></i>'
      : '<i class="bi bi-person" aria-hidden="true"></i>';
    const minimize = document.createElement('button');
    minimize.type = 'button';
    minimize.className = 'friends-chat-window__header-btn';
    minimize.title = 'Thu nhỏ';
    minimize.setAttribute('aria-label', 'Thu nhỏ cửa sổ chat');
    minimize.innerHTML = '<i class="bi bi-dash-lg" aria-hidden="true"></i>';
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'friends-chat-window__header-btn';
    close.title = 'Đóng';
    close.setAttribute('aria-label', 'Đóng cửa sổ chat');
    close.innerHTML = '<i class="bi bi-x-lg" aria-hidden="true"></i>';
    actions.append(profile, minimize, close);
    header.append(identity, actions);

    const messages = document.createElement('div');
    messages.className = 'friends-chat-window__messages';
    messages.setAttribute('role', 'log');
    messages.setAttribute('aria-live', 'polite');
    messages.setAttribute('aria-label', (conversation.type === 'group' ? 'Tin nhắn nhóm ' : 'Tin nhắn với ') + conversation.name);
    const loading = document.createElement('p');
    loading.className = 'friends-chat-window__system';
    loading.textContent = 'Đang tải tin nhắn…';
    messages.appendChild(loading);

    const statusLine = document.createElement('div');
    statusLine.className = 'friends-chat-window__feedback';
    statusLine.setAttribute('aria-live', 'polite');

    const form = document.createElement('form');
    form.className = 'friends-chat-window__composer';
    const input = document.createElement('input');
    input.type = 'text';
    input.disabled = true;
    input.maxLength = 1000;
    input.autocomplete = 'off';
    input.placeholder = conversation.type === 'group' ? 'Nhắn tin vào nhóm…' : 'Nhắn tin…';
    input.setAttribute('aria-label', 'Tin nhắn gửi ' + conversation.name);
    const send = document.createElement('button');
    send.type = 'submit';
    send.disabled = true;
    send.title = 'Gửi tin nhắn';
    send.setAttribute('aria-label', 'Gửi tin nhắn');
    send.innerHTML = '<i class="bi bi-send-fill" aria-hidden="true"></i>';
    form.append(input, send);

    win.append(header, messages, statusLine, form);
    dock.appendChild(win);

    const state = {
      type: conversation.type,
      id: String(conversation.id),
      key: conversationKey(conversation.type, conversation.id),
      name: conversation.name,
      avatar: conversation.avatar || DEFAULT_AVATAR,
      win, messages, statusLine, input, send,
      seenIds: new Set(), pendingIds: new Map(), liveQueue: [],
      historyLoaded: false, historyLoading: false
    };
    win._alouteChatState = state;

    minimize.addEventListener('click', () => {
      const minimized = win.classList.toggle('is-minimized');
      minimize.innerHTML = minimized ? '<i class="bi bi-arrows-angle-expand" aria-hidden="true"></i>' : '<i class="bi bi-dash-lg" aria-hidden="true"></i>';
      minimize.title = minimized ? 'Mở rộng' : 'Thu nhỏ';
      minimize.setAttribute('aria-label', minimized ? 'Mở rộng cửa sổ chat' : 'Thu nhỏ cửa sổ chat');
      if (!minimized) {
        input.focus();
        markConversationRead(state);
      }
    });
    close.addEventListener('click', () => {
      openWindows.delete(state.key);
      win.remove();
    });
    identity.addEventListener('click', () => {
      win.classList.remove('is-minimized');
      input.focus();
    });
    input.addEventListener('input', () => { send.disabled = !input.value.trim(); });
    form.addEventListener('submit', async event => {
      event.preventDefault();
      await sendMessage(state);
    });

    loadHistory(state);
    return win;
  }

  async function loadHistory(state) {
    if (state.historyLoading) return;
    state.historyLoading = true;
    try {
      const historyPath = state.type === 'group'
        ? '/api/chat/group/' + encodeURIComponent(state.id) + '/messages'
        : '/api/chat/messages/' + encodeURIComponent(state.id);
      const history = await Aloute.api(historyPath);
      state.messages.replaceChildren();
      (Array.isArray(history) ? history : []).forEach(message => appendMessage(state, message, false));
      if (!state.messages.children.length) showSystemMessage(state, 'Chưa có tin nhắn. Hãy gửi lời chào nhé!');
      state.historyLoaded = true;
      state.input.disabled = false;
      state.send.disabled = !state.input.value.trim();
      const queued = state.liveQueue.splice(0);
      queued.forEach(message => appendMessage(state, message, false));
      await markConversationRead(state);
      scrollToBottom(state.messages);
    } catch (error) {
      state.messages.replaceChildren();
      showSystemMessage(state, error.message || 'Không tải được tin nhắn.');
      setFeedback(state, 'Không tải được lịch sử tin nhắn.', true);
      state.input.disabled = false;
      state.historyLoaded = true;
      state.liveQueue.splice(0).forEach(message => appendMessage(state, message, false));
    } finally {
      state.historyLoading = false;
    }
  }

  function showSystemMessage(state, text) {
    const line = document.createElement('p');
    line.className = 'friends-chat-window__system';
    line.textContent = text;
    state.messages.appendChild(line);
  }

  function appendLinkedText(container, text) {
    const value = String(text || '');
    const urlPattern = /(https?:\/\/[^\s]+)/g;
    let cursor = 0;
    let match;
    while ((match = urlPattern.exec(value)) !== null) {
      if (match.index > cursor) container.appendChild(document.createTextNode(value.slice(cursor, match.index)));
      const anchor = document.createElement('a');
      let rawUrl = match[0];
      let trailing = '';
      while (/[),.!?]$/.test(rawUrl)) { trailing = rawUrl.slice(-1) + trailing; rawUrl = rawUrl.slice(0, -1); }
      try {
        const parsed = new URL(rawUrl);
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('invalid scheme');
        anchor.href = parsed.href;
        anchor.target = '_blank';
        anchor.rel = 'noopener noreferrer';
        anchor.textContent = rawUrl;
        container.appendChild(anchor);
        if (trailing) container.appendChild(document.createTextNode(trailing));
      } catch (_) {
        container.appendChild(document.createTextNode(match[0]));
      }
      cursor = match.index + match[0].length;
    }
    if (cursor < value.length) container.appendChild(document.createTextNode(value.slice(cursor)));
  }

  function appendMessage(state, message, pending) {
    if (!message) return null;
    const messageId = message.id == null ? '' : String(message.id);
    const clientId = message.clientId == null ? '' : String(message.clientId);
    if (messageId && state.seenIds.has(messageId)) return null;

    if (!pending && clientId) {
      const optimistic = state.pendingIds.get(clientId);
      if (optimistic) {
        optimistic.classList.remove('is-pending');
        optimistic.dataset.messageId = messageId;
        optimistic.querySelector('.friends-chat-window__delivery')?.remove();
        if (String(message.senderId) === String(document.body.dataset.currentUserId || '')) {
          setMessageReceipt(optimistic, cacheReceipt(messageId, receiptLabel(message)));
          state.lastMineRow = optimistic;
        }
        state.pendingIds.delete(clientId);
        if (messageId) state.seenIds.add(messageId);
        return optimistic;
      }
    }

    const oldSystem = state.messages.querySelector('.friends-chat-window__system');
    if (oldSystem) oldSystem.remove();
    const me = String(document.body.dataset.currentUserId || '');
    const mine = String(message.senderId) === me;
    const row = document.createElement('div');
    row.className = 'friends-chat-window__message-row ' + (mine ? 'is-mine' : 'is-theirs');
    if (messageId) row.dataset.messageId = messageId;
    if (clientId) row.dataset.clientId = clientId;
    if (message.senderId != null) row.dataset.senderId = String(message.senderId);
    if (pending) row.classList.add('is-pending');

    if (!mine) {
      const senderAvatar = document.createElement('img');
      senderAvatar.className = 'friends-chat-window__message-avatar';
      senderAvatar.src = state.type === 'group'
        ? (message.senderAvatarUrl || DEFAULT_AVATAR)
        : (state.avatar || message.senderAvatarUrl || DEFAULT_AVATAR);
      senderAvatar.alt = '';
      senderAvatar.loading = 'lazy';
      senderAvatar.onerror = () => { senderAvatar.onerror = null; senderAvatar.src = DEFAULT_AVATAR; };
      senderAvatar.title = state.type === 'group' ? (message.senderFullName || message.senderUsername || 'Thành viên') : state.name;
      row.appendChild(senderAvatar);
    }

    const contentWrap = document.createElement('div');
    contentWrap.className = 'friends-chat-window__message-content';
    if (state.type === 'group' && !mine) {
      const senderName = document.createElement('small');
      senderName.className = 'friends-chat-window__sender-name';
      senderName.textContent = message.senderFullName || message.senderUsername || 'Thành viên';
      contentWrap.appendChild(senderName);
    }
    const bubble = document.createElement('div');
    bubble.className = 'friends-chat-window__bubble';
    const content = String(message.content || '');
    if (content) appendLinkedText(bubble, content);
    if (message.mediaUrl) {
      const mediaLink = document.createElement('a');
      mediaLink.href = message.mediaUrl;
      mediaLink.target = '_blank';
      mediaLink.rel = 'noopener noreferrer';
      mediaLink.textContent = message.mediaName || (message.mediaType === 'image' ? 'Xem ảnh' : 'Xem tệp đính kèm');
      bubble.appendChild(mediaLink);
    }
    if (!content && !message.mediaUrl) bubble.textContent = 'Tin nhắn';
    contentWrap.appendChild(bubble);
    if (mine) {
      const delivery = document.createElement('small');
      delivery.className = 'friends-chat-window__delivery';
      delivery.textContent = pending ? 'Đang gửi…' : cacheReceipt(messageId, receiptLabel(message));
      contentWrap.appendChild(delivery);
      if (pending && clientId) state.pendingIds.set(clientId, row);
      state.lastMineRow = row;
    }
    row.appendChild(contentWrap);
    state.messages.appendChild(row);
    if (messageId) state.seenIds.add(messageId);
    scrollToBottom(state.messages);
    return row;
  }

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

  function receiptLabel(message) {
    if (!message) return 'Đã gửi';
    if (message.read === true || Number(message.readCount || 0) > 0) return 'Đã xem';
    if (message.delivered === true || Number(message.deliveredCount || 0) > 0) return 'Đã nhận';
    return message.id ? 'Đã gửi' : 'Đang gửi…';
  }

  function setMessageReceipt(row, label) {
    if (!row) return;
    let receipt = row.querySelector('.friends-chat-window__delivery');
    if (!receipt) {
      const wrap = row.querySelector('.friends-chat-window__message-content');
      if (!wrap || !row.classList.contains('is-mine')) return;
      receipt = document.createElement('small');
      receipt.className = 'friends-chat-window__delivery';
      wrap.appendChild(receipt);
    }
    receipt.textContent = label || '';
    receipt.hidden = !label;
  }

  function updateOpenWindowReceipts(payload, label) {
    const ids = new Set((Array.isArray(payload.messageIds) ? payload.messageIds : [payload.messageId])
      .filter(id => id != null).map(String));
    if (!ids.size) return;
    ids.forEach(id => cacheReceipt(id, label));
    openWindows.forEach(win => {
      if (!win.isConnected || !win._alouteChatState) return;
      const state = win._alouteChatState;
      state.messages.querySelectorAll('.friends-chat-window__message-row.is-mine[data-message-id]').forEach(row => {
        if (ids.has(String(row.dataset.messageId))) setMessageReceipt(row, cacheReceipt(row.dataset.messageId, label));
      });
    });
  }

  async function sendMessage(state) {
    const content = state.input.value.trim();
    if (!content || state.send.disabled) return;
    const clientId = (window.crypto && typeof window.crypto.randomUUID === 'function')
      ? window.crypto.randomUUID()
      : ('mini-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10));
    const payload = state.type === 'group'
      ? { groupId: Number(state.id), content, clientId }
      : { receiverId: Number(state.id), content, clientId };
    state.input.value = '';
    state.send.disabled = true;
    state.statusLine.textContent = '';
    appendMessage(state, state.type === 'group'
      ? { senderId: document.body.dataset.currentUserId, groupId: Number(state.id), content, clientId }
      : { senderId: document.body.dataset.currentUserId, receiverId: state.id, content, clientId }, true);
    try {
      if (!window.Aloute || typeof Aloute.onRealtimeReady !== 'function') throw new Error('Kết nối chat chưa sẵn sàng. Hãy tải lại trang.');
      await sendRealtime(payload);
    } catch (error) {
      const pending = state.pendingIds.get(clientId);
      if (pending) {
        pending.classList.remove('is-pending');
        pending.classList.add('has-error');
        const delivery = pending.querySelector('.friends-chat-window__delivery');
        if (delivery) delivery.textContent = 'Chưa gửi';
      }
      setFeedback(state, error.message || 'Không gửi được tin nhắn.', true);
      state.input.value = content;
    } finally {
      state.send.disabled = !state.input.value.trim();
      state.input.focus();
    }
  }

  function sendRealtime(payload) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const timeout = window.setTimeout(() => {
        settled = true;
        reject(new Error('Kết nối chat đang chậm. Vui lòng thử gửi lại.'));
      }, 10000);
      try {
        Aloute.connectRealtime();
        Aloute.onRealtimeReady(client => {
          if (settled) return;
          try {
            client.send('/app/chat.send', {}, JSON.stringify(payload));
            settled = true;
            window.clearTimeout(timeout);
            resolve(true);
          } catch (error) {
            settled = true;
            window.clearTimeout(timeout);
            reject(error);
          }
        });
      } catch (error) {
        settled = true;
        window.clearTimeout(timeout);
        reject(error);
      }
    });
  }

  function setFeedback(state, message, isError) {
    state.statusLine.textContent = message || '';
    state.statusLine.classList.toggle('is-error', !!isError);
    if (message) {
      clearTimeout(state.feedbackTimer);
      state.feedbackTimer = window.setTimeout(() => { state.statusLine.textContent = ''; }, 4500);
    }
  }

  function scrollToBottom(element) {
    window.requestAnimationFrame(() => { element.scrollTop = element.scrollHeight; });
  }

  function syncChatBadges() {
    if (window.Aloute && typeof Aloute.refreshNavBadges === 'function') Aloute.refreshNavBadges(true);
  }

  function markRead(userId) {
    return Aloute.api('/api/chat/read/' + encodeURIComponent(userId), { method: 'POST' })
      .then(value => { syncChatBadges(); return value; }).catch(() => {});
  }

  function markGroupRead(groupId) {
    return Aloute.api('/api/chat/group/' + encodeURIComponent(groupId) + '/read', { method: 'POST' })
      .then(value => { syncChatBadges(); return value; }).catch(() => {});
  }

  function markConversationRead(state) {
    return state.type === 'group' ? markGroupRead(state.id) : markRead(state.id);
  }

  function openExistingOrCreate(conversation) {
    const dock = currentDock();
    if (!dock) {
      window.location.href = conversation.type === 'group'
        ? '/chat?groupId=' + encodeURIComponent(conversation.id)
        : '/chat?userId=' + encodeURIComponent(conversation.id);
      return;
    }
    const key = conversationKey(conversation.type, conversation.id);
    const existing = openWindows.get(key);
    if (existing && existing.isConnected) {
      existing.classList.remove('is-minimized');
      existing.querySelector('.friends-chat-window__composer input')?.focus();
      // Reopening an existing/minimized conversation must clear its unread badge too.
      if (existing._alouteChatState) markConversationRead(existing._alouteChatState);
      return;
    }
    openWindows.delete(key);
    const viewportWidth = window.innerWidth || document.documentElement.clientWidth || 1024;
    // 440px windows need ~892px for two and ~1,368px plus margins for three.
    const maxWindows = viewportWidth <= 940 ? 1 : (viewportWidth < 1450 ? 2 : MAX_DESKTOP_WINDOWS);
    while (Array.from(openWindows.values()).filter(win => win.isConnected).length >= maxWindows) {
      const oldestKey = openWindows.keys().next().value;
      const oldest = openWindows.get(oldestKey);
      openWindows.delete(oldestKey);
      if (oldest && oldest.isConnected) oldest.remove();
    }
    const win = createWindow(Object.assign({}, conversation, { key }), dock);
    openWindows.set(key, win);
    const state = win._alouteChatState;
    if (state && state.historyLoading) setFeedback(state, 'Đang kết nối trò chuyện…');
  }

  async function openFriendChat(button) {
    const id = String(button.dataset.userId || '');
    if (!id) return;
    openExistingOrCreate({
      type: 'friend', id,
      username: button.dataset.username || '',
      name: button.dataset.fullname || button.dataset.username || 'Bạn bè',
      avatar: button.dataset.avatar || button.querySelector('img')?.src || DEFAULT_AVATAR,
      online: button.dataset.online === 'true'
    });
  }

  async function openGroupChat(button) {
    const id = String(button.dataset.groupId || '');
    if (!id) return;
    openExistingOrCreate({
      type: 'group', id,
      name: button.dataset.groupName || button.querySelector('.feed-group-item__body strong')?.textContent?.trim() || 'Nhóm chat',
      avatar: button.dataset.groupAvatar || button.querySelector('img')?.src || DEFAULT_AVATAR,
      online: false
    });
  }

  // Delegated fallback for dynamically inserted friend and group rows.
  document.addEventListener('click', event => {
    const target = event.target;
    if (!target || typeof target.closest !== 'function') return;
    const groupButton = target.closest('[data-group-chat-open]');
    if (groupButton && groupButton.dataset.groupChatClickBound !== 'true') {
      event.preventDefault();
      event.stopPropagation();
      handleGroupOpen(groupButton);
      return;
    }
    const friendButton = target.closest('#friendsPanel [data-friend-chat-open]');
    if (friendButton && friendButton.dataset.chatClickBound !== 'true') {
      event.preventDefault();
      event.stopPropagation();
      handleFriendOpen(friendButton);
    }
  }, true);

  document.addEventListener('input' , event => {
    if (event.target && event.target.id === 'friendsPanelSearch') applyFriendSearch();
  });

  document.addEventListener('aloute:presence', event => {
    const payload = event.detail || {};
    if (payload.userId != null) setFriendPresence(payload.userId, !!payload.online);
  });

  document.addEventListener('aloute:message', event => {
    const message = event.detail || {};
    const me = String(document.body.dataset.currentUserId || '');
    const sender = String(message.senderId == null ? '' : message.senderId);
    const isGroup = message.groupId != null;
    const peerId = isGroup ? String(message.groupId) : (sender === me ? String(message.receiverId || '') : sender);
    const key = conversationKey(isGroup ? 'group' : 'friend', peerId);
    const win = openWindows.get(key);
    if (!win || !win.isConnected) return;
    const state = win._alouteChatState;
    if (!state) return;
    if (state.historyLoading) {
      state.liveQueue.push(message);
      return;
    }
    const existingPending = message.clientId && state.pendingIds.get(String(message.clientId));
    if (existingPending) {
      appendMessage(state, message, false);
      return;
    }
    if (message.id != null && state.seenIds.has(String(message.id))) return;
    appendMessage(state, message, false);
    if (!win.classList.contains('is-minimized') && sender !== me && !document.hidden) {
      markConversationRead(state);
    }
  });

  document.addEventListener('aloute:delivered', event => {
    updateOpenWindowReceipts(event.detail || {}, 'Đã nhận');
  });

  document.addEventListener('aloute:read', event => {
    updateOpenWindowReceipts(event.detail || {}, 'Đã xem');
    syncChatBadges();
  });

  document.addEventListener('aloute:chat-error' , event => {
    const payload = event.detail || {};
    if (!payload.clientId) return;
    openWindows.forEach(win => {
      if (!win.isConnected || !win._alouteChatState) return;
      const state = win._alouteChatState;
      const pending = state.pendingIds.get(String(payload.clientId));
      if (!pending) return;
      pending.classList.remove('is-pending');
      pending.classList.add('has-error');
      const delivery = pending.querySelector('.friends-chat-window__delivery');
      if (delivery) delivery.textContent = 'Chưa gửi';
      setFeedback(state, payload.message || 'Không gửi được tin nhắn.', true);
    });
  });

  document.addEventListener('aloute:navigated', initializePanel);
  document.addEventListener('DOMContentLoaded', initializePanel);
})();
