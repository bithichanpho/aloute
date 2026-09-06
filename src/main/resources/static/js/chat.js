// ============================================
// ALOUTE — chat.js
// Hiện tại chỉ xử lý hiển thị tin nhắn phía client (demo giao diện).
// Phần kết nối WebSocket/STOMP thật được để sẵn dưới dạng chú thích —
// Người 3 bật lên khi ChatController.java (STOMP) đã code xong.
// ============================================

function sendMessage() {
  const input = document.getElementById("messageInput");
  const text = input.value.trim();
  if (!text) return;

  appendBubble(text, "mine");
  input.value = "";

  // ----- Khi nối WebSocket thật, thay đoạn appendBubble ở trên bằng: -----
  // stompClient.send("/app/chat.send", {}, JSON.stringify({
  //   receiverId: currentConversationUserId,
  //   content: text
  // }));
  // Tin nhắn nên chỉ hiện lên khung chat SAU KHI server xác nhận đã lưu,
  // để tránh mất tin nhắn nếu mất kết nối giữa chừng.

  // Demo: giả lập người kia trả lời sau 1 giây
  setTimeout(() => {
    appendBubble("(demo) Đã nhận: " + text, "theirs");
  }, 1000);
}

function appendBubble(text, who) {
  const list = document.getElementById("messageList");
  const bubble = document.createElement("div");
  bubble.className = "bubble bubble--" + who;
  bubble.textContent = text;
  list.appendChild(bubble);
  list.scrollTop = list.scrollHeight;
}

document.addEventListener("DOMContentLoaded", () => {
  const input = document.getElementById("messageInput");
  if (input) {
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") sendMessage();
    });
  }
});

/* ============================================================
   KHUNG KẾT NỐI WEBSOCKET/STOMP THẬT — bật khi backend đã sẵn sàng
   ============================================================

   Cần thêm 2 thư viện client vào trang (qua CDN hoặc npm):
   - sockjs-client
   - @stomp/stompjs

   let stompClient = null;

   function connect() {
     const socket = new SockJS('/ws');           // khớp endpoint đăng ký trong WebSocketConfig.java
     stompClient = Stomp.over(socket);
     stompClient.connect({ Authorization: 'Bearer ' + getJwtToken() }, () => {
       stompClient.subscribe('/user/queue/messages', (message) => {
         const body = JSON.parse(message.body);
         appendBubble(body.content, body.senderId === currentUserId ? 'mine' : 'theirs');
       });
     });
   }

   document.addEventListener("DOMContentLoaded", connect);
   ============================================================ */