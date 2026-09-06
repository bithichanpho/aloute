/* ============================================================
   ALOUTE — chat.js
   Chi chay tren trang /chat. Dung chung ket noi STOMP da duoc
   app.js khoi tao san (Aloute.onRealtimeReady) de gui/nhan tin
   nhan real-time, khong tao them ket noi WebSocket thu 2.
   ============================================================ */

(function () {
  const chatList = document.getElementById('chatList');
  const chatThread = document.getElementById('chatThread');
  const emptyState = document.getElementById('chatEmptyState');
  const template = document.getElementById('chatThreadTemplate');
  if (!chatList || !chatThread || !template) return;

  const currentUserId = document.body.dataset.currentUserId;
  let activeUserId = null;
  let stompClientRef = null;

  Aloute.onRealtimeReady(function (client) {
    stompClientRef = client;
  });

  // ---------- Chọn 1 hội thoại ----------
  chatList.querySelectorAll('.chat-item[data-user-id]').forEach(item => {
    item.addEventListener('click', () => openConversation(item));
  });

  async function openConversation(item) {
    chatList.querySelectorAll('.chat-item').forEach(i => i.classList.remove('chat-item--active'));
    item.classList.add('chat-item--active');
    chatList.classList.add('has-active-thread');

    const userId = item.dataset.userId;
    const username = item.dataset.username || 'Người dùng';
    const avatar = item.dataset.avatar || 'https://i.pravatar.cc/150?img=32';
    activeUserId = userId;

    // Xóa badge chưa đọc của hội thoại này ngay khi mở
    const unreadEl = item.querySelector('.chat-item__unread');
    if (unreadEl) unreadEl.remove();

    renderThreadShell(username, avatar);

    const messageList = document.getElementById('messageList');
    messageList.innerHTML = '<div class="empty-state">Đang tải tin nhắn...</div>';

    try {
      const messages = await Aloute.api('/api/chat/messages/' + userId);
      renderMessages(messages);
      Aloute.api('/api/chat/read/' + userId, { method: 'POST' }).catch(() => {});
    } catch (err) {
      messageList.innerHTML = '<div class="empty-state">Không tải được tin nhắn: ' + Aloute.escapeHtml(err.message) + '</div>';
    }

    document.getElementById('sendMessageBtn').addEventListener('click', () => sendCurrentMessage());
    document.getElementById('messageInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); sendCurrentMessage(); }
    });

    const backBtn = document.getElementById('chatBackBtn');
    if (backBtn) {
      backBtn.style.display = window.innerWidth <= 900 ? 'flex' : 'none';
      backBtn.addEventListener('click', () => {
        chatList.classList.remove('has-active-thread');
      });
    }
  }

  function renderThreadShell(username, avatar) {
    chatThread.innerHTML = '';
    const clone = template.content.cloneNode(true);
    chatThread.appendChild(clone);
    document.getElementById('chatThreadAvatar').src = avatar;
    document.getElementById('chatThreadName').textContent = username;
  }

  function renderMessages(messages) {
    const messageList = document.getElementById('messageList');
    if (!messages.length) {
      messageList.innerHTML = '<div class="empty-state">Chưa có tin nhắn nào, hãy bắt đầu trò chuyện!</div>';
      return;
    }
    messageList.innerHTML = messages.map(bubbleHtml).join('');
    messageList.scrollTop = messageList.scrollHeight;
  }

  function bubbleHtml(msg) {
    const mine = String(msg.senderId) === String(currentUserId);
    return '<div class="bubble ' + (mine ? 'bubble--mine' : 'bubble--theirs') + '">' +
      Aloute.escapeHtml(msg.content) + '</div>';
  }

  function appendBubble(msg) {
    const messageList = document.getElementById('messageList');
    if (!messageList) return;
    const empty = messageList.querySelector('.empty-state');
    if (empty) empty.remove();
    messageList.insertAdjacentHTML('beforeend', bubbleHtml(msg));
    messageList.scrollTop = messageList.scrollHeight;
  }

  function sendCurrentMessage() {
    const input = document.getElementById('messageInput');
    const content = input.value.trim();
    if (!content || !activeUserId) return;

    if (!stompClientRef) {
      Aloute.toast('Đang kết nối lại, vui lòng thử lại sau giây lát', true);
      return;
    }

    stompClientRef.send('/app/chat.send', {}, JSON.stringify({
      receiverId: Number(activeUserId),
      content: content
    }));
    input.value = '';
    // Không append lạc quan ở đây — bubble sẽ được thêm khi server echo về
    // qua sự kiện 'aloute:message' bên dưới (đúng thiết kế: chỉ hiện SAU khi lưu thành công).
  }

  // ---------- Nhận tin nhắn real-time (app.js phát ra sự kiện này) ----------
  document.addEventListener('aloute:message', (e) => {
    const msg = e.detail;
    const otherId = String(msg.senderId) === String(currentUserId) ? String(msg.receiverId) : String(msg.senderId);

    if (activeUserId && otherId === String(activeUserId)) {
      appendBubble(msg);
      if (String(msg.senderId) !== String(currentUserId)) {
        Aloute.api('/api/chat/read/' + otherId, { method: 'POST' }).catch(() => {});
      }
      return;
    }

    // Tin nhắn thuộc hội thoại khác đang không mở — cập nhật preview + badge nếu
    // hội thoại đó đã có trong danh sách, ngược lại tải lại trang cho đơn giản.
    const item = chatList.querySelector('.chat-item[data-user-id="' + otherId + '"]');
    if (item) {
      const preview = item.querySelector('.chat-item__preview');
      if (preview) preview.textContent = msg.content;
      if (String(msg.senderId) !== String(currentUserId)) {
        let badge = item.querySelector('.chat-item__unread');
        if (!badge) {
          badge = document.createElement('span');
          badge.className = 'chat-item__unread';
          badge.textContent = '1';
          item.appendChild(badge);
        } else {
          badge.textContent = String((parseInt(badge.textContent, 10) || 0) + 1);
        }
      }
    } else {
      location.reload();
    }
  });
})();