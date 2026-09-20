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
  let realtimeStarting = false;
  let realtimeLibsPromise = null;
  const readyCallbacks = [];

  // SockJS + Stomp nang ~60KB va CHI can cho realtime. Truoc day chung duoc nhung
  // bang <script> cung trong layout nen MOI trang (ke ca trang tinh nhu /profile,
  // /search) deu phai tai va parse chung truoc khi app.js chay. Gio chi tai khi
  // that su sap ket noi, va tai bat dong bo nen khong chan hien thi trang.
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

  /** Tai thu vien (neu chua co) roi mo ket noi STOMP. */
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

      // Thay avatar theo thời gian thực khi bạn bè đổi ảnh đại diện.
      stompClient.subscribe('/user/queue/avatar-updates', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:avatar-update', { detail: payload }));
      });

      // Tín hiệu WebRTC cho cuộc gọi 1-1: offer / answer / ICE / hangup
      stompClient.subscribe('/user/queue/call', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:call-signal', { detail: payload }));
      });

      // Loi gui tin nhan (vd: chua la ban be, noi dung rong...) - xem ChatController#sendMessage
      stompClient.subscribe('/user/queue/errors', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:chat-error', { detail: payload }));
      });

      // Nhom vua duoc tao / them nguoi / co nguoi roi - xem GroupService#broadcastGroupUpdate
      stompClient.subscribe('/user/queue/group-updates', function (frame) {
        const payload = JSON.parse(frame.body);
        document.dispatchEvent(new CustomEvent('aloute:group-update', { detail: payload }));
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

  const REACTION_EMOJI = { LIKE: '👍', LOVE: '❤️', HAHA: '😆', WOW: '😮', SAD: '😢', ANGRY: '😠' };

  function applyReactionUI(likeBtn, type, animate) {
    likeBtn.classList.toggle('is-reacted', !!type);
    Array.from(likeBtn.classList).forEach(c => { if (c.indexOf('reacted--') === 0) likeBtn.classList.remove(c); });
    if (type) likeBtn.classList.add('reacted--' + type);
    const emojiSpan = likeBtn.querySelector('.action--like__emoji');
    if (emojiSpan) {
      emojiSpan.textContent = type ? (REACTION_EMOJI[type] || '👍') : '';
      if (type && animate) {
        // Chạy lại animation "nảy" mỗi lần người dùng chủ động chọn cảm xúc.
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

  // Đóng bảng chọn emoji đang mở (long-press trên mobile) khi bấm ra ngoài — đăng ký
  // MỘT LẦN DUY NHẤT cho toàn trang (event delegation), thay vì mỗi bài viết tự thêm
  // 1 listener riêng trên document (sẽ tích tụ hàng chục listener khi cuộn tải thêm bài).
  document.addEventListener('click', (e) => {
    document.querySelectorAll('.reaction-picker--open').forEach(picker => {
      if (!picker.parentElement || !picker.parentElement.contains(e.target)) {
        picker.classList.remove('reaction-picker--open');
      }
    });
  });

  function initFeedInteractions() {
    document.querySelectorAll('[data-post-id]').forEach(postEl => {
      // Với repost: data-post-id là document quan hệ (để có thể xóa lượt share),
      // data-action-post-id mới là ID của bài gốc dùng cho like/comment/repost/share.
      const postId = postEl.dataset.actionPostId || postEl.dataset.postId;

      const reaction = postEl.querySelector('.reaction');
      if (reaction && !reaction.dataset.bound) {
        reaction.dataset.bound = '1';

        const likeBtn = reaction.querySelector('.action--like');
        const picker = reaction.querySelector('.reaction-picker');
        const countEl = likeBtn.querySelector('span:not(.action--like__emoji)');
        const initialMatch = /reacted--([A-Z]+)/.exec(likeBtn.className);
        const initialType = initialMatch ? initialMatch[1] : null;
        applyReactionUI(likeBtn, initialType);

        // Optimistic UI + queue trạng thái mong muốn, cùng mô hình với like cũ nhưng
        // "trạng thái" giờ là 1 trong 6 loại emoji (hoặc null = không reaction) thay vì boolean.
        const state = {
          desired: initialType,
          serverType: initialType,
          serverCount: parseInt(countEl.dataset.raw || countEl.textContent, 10) || 0,
          inFlight: false
        };

        function render(animate) {
          // Đổi từ loại A sang loại B không đổi tổng số; chỉ +1/-1 khi bật/tắt hẳn.
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

        // ----- Bảng chọn cảm xúc kiểu Facebook -----
        // Desktop: rê chuột vào nút Thích một chút là bảng hiện ra, rê chuột LÊN emoji để chọn;
        //          rời chuột ra ngoài thì bảng tự đóng sau một khoảng trễ ngắn (không cần bấm).
        // Mobile : giữ (long-press) nút Thích để mở bảng.
        const canHover = !!(window.matchMedia && window.matchMedia('(hover: hover) and (pointer: fine)').matches);
        const OPEN_DELAY = 250;   // rê chuột vào nút Thích bao lâu thì mở bảng
        const CLOSE_DELAY = 300;  // rời chuột khỏi vùng (nút + bảng) bao lâu thì đóng bảng
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

        // Bấm nút chính (icon): đã có reaction nào đó -> bỏ; chưa có -> đặt Thích (LIKE) mặc định.
        // Giống cách Facebook xử lý khi bấm thẳng vào icon thay vì chọn từ bảng emoji.
        likeBtn.addEventListener('click', (e) => {
          if (longPressed) { longPressed = false; e.preventDefault(); return; } // vừa long-press mở bảng, không tính là bấm
          closePicker();
          state.desired = state.desired ? null : 'LIKE';
          render(true);
          flushReactionQueue();
        });

        if (picker) {
          picker.querySelectorAll('.reaction-picker__emoji').forEach((emojiBtn, index) => {
            // Nhãn tên cảm xúc hiển thị bằng CSS (thay cho tooltip mặc định của trình duyệt).
            if (!emojiBtn.dataset.label) emojiBtn.dataset.label = emojiBtn.getAttribute('title') || '';
            emojiBtn.removeAttribute('title');
            emojiBtn.style.setProperty('--i', String(index));

            emojiBtn.addEventListener('click', (e) => {
              e.stopPropagation();
              const type = emojiBtn.dataset.reaction;
              // Bấm lại đúng emoji đang chọn -> bỏ reaction (giống bấm 2 lần để tắt).
              state.desired = state.desired === type ? null : type;
              closePicker();
              render(true);
              flushReactionQueue();
            });
          });

          if (canHover) {
            // mouseenter/mouseleave gắn trên wrapper .reaction (chứa cả nút lẫn bảng) và bảng có
            // "cầu nối" CSS lấp khe hở -> kéo chuột thẳng từ nút lên emoji không làm bảng đóng.
            reaction.addEventListener('mouseenter', scheduleOpen);
            reaction.addEventListener('mouseleave', scheduleClose);
          }

          // Cảm ứng: giữ khoảng nửa giây để mở bảng.
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

        // Cùng mô hình với thả cảm xúc: đổi giao diện NGAY khi bấm (optimistic), request chạy
        // nền theo hàng đợi. Bấm liên tục vẫn mượt — chỉ gửi khi trạng thái mong muốn còn lệch
        // trạng thái server, và lỗi thì tự hoàn tác về đúng trạng thái server.
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

        function updateRepostBanner(actors) {
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
          if (actors.length > 3) {
            banner.appendChild(document.createTextNode(' cùng '));
            const details = document.createElement('details');
            details.className = 'post__repost-others';
            const summary = document.createElement('summary');
            summary.textContent = (actors.length - 3) + ' người khác';
            details.appendChild(summary);
            const list = document.createElement('div');
            list.className = 'post__repost-others-list';
            actors.slice(3).forEach(function(actor) {
              const link = document.createElement('a');
              link.href = '/profile/' + encodeURIComponent(actor.userId);
              link.textContent = actor.username || 'người dùng';
              list.appendChild(link);
            });
            details.appendChild(list);
            banner.appendChild(details);
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
            // Endpoint /repost là TOGGLE: mỗi lần gọi đảo trạng thái, nên lặp cho tới khi
            // trạng thái server khớp với trạng thái người dùng đang muốn.
            while (rState.desired !== rState.serverState) {
              const requested = rState.desired;
              try {
                const result = await api('/api/posts/' + originalPostId + '/repost', {
                  method: 'POST',
                  body: {}
                });
                rState.serverState = !!result.reposted;
                rState.serverCount = Number(result.repostCount) || 0;
                updateRepostBanner(result.reposters || []);
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

  async function loadComments(postId, listEl, page = 0) {
    if (page === 0) listEl.innerHTML = '<span class="comment-list__loading">Dang tai binh luan...</span>';
    try {
      const comments = await api('/api/posts/' + postId + '/comments?page=' + page + '&size=50');
      listEl.querySelector('[data-more-comments]')?.remove();
      if (!comments.length && page === 0) {
        listEl.innerHTML = '<span class="comment-list__empty">Chua co binh luan nao.</span>';
        return;
      }
      if (page === 0) listEl.innerHTML = '';
      listEl.insertAdjacentHTML('beforeend', comments.map(c => (
        '<div class="comment-item">' +
        '  <img src="' + escapeHtml(c.userAvatarUrl || '/images/avatar_default.png') + '" alt="avatar">' +
        '  <div class="comment-item__body">' +
        '    <span class="comment-item__username">' + escapeHtml(c.username || '') + '</span>' +
        '    <p>' + escapeHtml(c.content) + '</p>' +
        '    <span class="comment-item__time">' + timeAgo(c.createdAt) + '</span>' +
        '  </div>' +
        '</div>'
      )).join(''));
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
      if (page === 0) listEl.innerHTML = '<span class="comment-list__empty">Khong tai duoc binh luan.</span>';
      else toast(err.message, true);
    }
  }

  /** O soan nhanh tren dau feed (chi text, muon them anh thi dung trang /compose day du). */
  function initQuickComposeBox() {
    const box = document.querySelector('.compose');
    // Ô soạn bài trong trang hội nhóm có xử lý riêng (initCommunityPages) — nếu để đây bắt,
    // bài viết sẽ bị đăng nhầm lên feed chung thay vì vào hội nhóm.
    if (!box || box.closest('.community-page')) return;
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
    btn.textContent = following ? 'Đang theo dõi' : 'Theo dõi';
  }

  // ============================================================
  // Nut "Ban than" (trang ca nhan, ket qua tim kiem) — mo hinh gui
  // loi moi / cho chap nhan, khac voi Follow o tren (bam la co ngay).
  // Trang thai: NONE | PENDING_SENT | PENDING_RECEIVED | ACCEPTED
  // ============================================================
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

    // Thanh "X đã gửi lời mời kết bạn thân" render sẵn trên trang cá nhân (chỉ có ở đó,
    // vì cần đủ chỗ cho 2 nút chấp nhận/từ chối cạnh nhau).
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
          if (!confirm('Từ chối lời mời kết bạn thân này?')) return;
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
        if (!confirm('Hủy lời mời kết bạn thân đã gửi?')) return;
        await api('/api/close-friends/' + userId, { method: 'DELETE' });
        applyCloseFriendState(btn, 'NONE');
        toast('Đã hủy lời mời');
      } else if (status === 'PENDING_RECEIVED') {
        await api('/api/close-friends/' + userId + '/accept', { method: 'POST' });
        applyCloseFriendState(btn, 'ACCEPTED');
        toast('Đã chấp nhận kết bạn thân');
      } else if (status === 'ACCEPTED') {
        if (!confirm('Xóa khỏi danh sách bạn thân?')) return;
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

  // ============================================================
  // Trang tim kiem (/search) — bo cuc kieu Threads:
  // o tim kiem + tab ket qua (Moi nguoi / Bai viet) + khu vuc kham pha
  // (Goi y cho ban & Xu huong) hien khi o tim kiem con trong.
  // ============================================================
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

    // Bam vao mot chu de xu huong -> dua tu khoa do vao o tim kiem
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

    async function runSearch() {
      showResults();

      const tab = currentTab;
      const key = keyword;
      const token = ++requestToken;

      if (cache[tab][key]) {
        paint(tab, cache[tab][key]);
        return;
      }

      resultsEl.innerHTML = searchSkeleton(tab);
      try {
        const url = (tab === 'posts' ? '/api/search/posts?q=' : '/api/search/users?q=') + encodeURIComponent(key);
        const data = await api(url);
        if (token !== requestToken) return;
        cache[tab][key] = data || [];
        paint(tab, cache[tab][key]);
      } catch (err) {
        if (token !== requestToken) return;
        resultsEl.innerHTML = discoverEmpty('Không tải được kết quả', err.message);
      }
    }

    function paint(tab, items) {
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
    }
  }

  // ---------- Khu vuc kham pha: goi y ket ban + xu huong ----------
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

    // Nut "Ban than" chi hien khi da la ban be (follow lan nhau) — va khong hien khi ho vua
    // gui loi moi cho minh (PENDING_RECEIVED), vi user-row nho khong du cho 2 nut chap
    // nhan/tu choi canh nhau; truong hop do phan hoi qua trang /activity hoac trang ca nhan.
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


  // ============================================================
  // Trang ca nhan — hop thoai danh sach ket noi
  // (Nguoi theo doi / Dang theo doi / Ban than)
  // ============================================================
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

  // ---------- Anh dai dien hong (URL Cloudinary chet, tai khoan cu...) -> ve anh mac dinh ----------
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
    }, true); // capture: su kien error cua <img> khong bubble
  }


  // ---------- Trang ca nhan: chuyen giua dong thoi gian / luoi / dang lai ----------
  function initProfileViewToggle() {
    const buttons = Array.from(document.querySelectorAll('[data-profile-view]'));
    const gridWrap = document.getElementById('profileGridWrap');
    const grid = document.getElementById('profileGrid');
    const list = document.getElementById('profilePosts');
    const repostsWrap = document.getElementById('profileRepostsWrap');
    if (!buttons.length || !list) return;

    function apply(view) {
      buttons.forEach(b => b.classList.toggle('is-active', b.dataset.profileView === view));
      if (gridWrap) gridWrap.hidden = view !== 'grid';
      if (repostsWrap) repostsWrap.hidden = view !== 'reposts';
      list.hidden = view !== 'list';
      const hash = view === 'grid' ? '#luoi' : (view === 'reposts' ? '#dang-lai' : '');
      try { history.replaceState(null, '', hash || location.pathname); } catch (e) {}
    }

    buttons.forEach(b => b.addEventListener('click', () => apply(b.dataset.profileView)));
    // MAC DINH: dong thoi gian (kieu threads). Luoi va Dang lai chi la che do xem phu.
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

  // ============================================================
  // Trang hoat dong / thong bao (/activity)
  // ============================================================
  function initActivityPage() {
    const markAllBtn = document.getElementById('markAllReadBtn');
    if (markAllBtn) {
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

    // Loi moi ket ban than gui thang trong thong bao -> chap nhan/tu choi ngay tai day,
    // khong can qua trang ca nhan cua nguoi gui.
    document.querySelectorAll('.notif-item__actions[data-sender-id]').forEach(actions => {
      const senderId = actions.dataset.senderId;
      const acceptBtn = actions.querySelector('.notif-item__accept');
      const declineBtn = actions.querySelector('.notif-item__decline');

      const disableBoth = () => {
        if (acceptBtn) acceptBtn.disabled = true;
        if (declineBtn) declineBtn.disabled = true;
      };
      // Lời mời có thể đã được xử lý ở nơi khác (trang cá nhân, tab khác...) từ trước khi
      // trang này tải — nếu bấm mà API báo không tìm thấy, chỉ cần ẩn 2 nút đi, không phải lỗi thật.
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
      const privateToggle = document.getElementById('editPrivateAccount');
      const payload = { fullName, bio };
      if (privateToggle) payload.privateAccount = privateToggle.checked;
      try {
        await api('/api/users/me', { method: 'PUT', body: payload });
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
  // Doi mat khau (trong trang /profile/edit)
  // ============================================================
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

      if (newPassword.length < 6) {
        errorBox.textContent = 'Mat khau moi phai co it nhat 6 ky tu.';
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
  // Bai moi tai them cung phai xuat hien trong tab "Luoi" neu co anh,
  // de bam vao o luoi luon nhay dung toi bai do trong dong thoi gian.
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
      let page = 1;
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          const url = new URL(button.dataset.url, window.location.origin);
          url.searchParams.set('page', page);
          const response = await fetch(url, { credentials: 'same-origin' });
          if (!response.ok || response.redirected) throw new Error('Khong tai duoc bai viet');
          const fragment = document.createElement('template');
          fragment.innerHTML = await response.text();
          const posts = fragment.content.querySelectorAll('.post');
          const count = posts.length;
          // Nut "Tai them" nam ngay trong container bai viet, nen luon chen TRUOC nut
          // de thu tu dong thoi gian khong bi vo.
          posts.forEach(post => button.before(post));
          appendGridTiles(posts);
          page++;
          if (count < 20) button.dataset.exhausted = '1';
          button.hidden = count < 20;
          initFeedInteractions();
          formatInitialCounts();
        } catch (err) {
          toast(err.message, true);
        } finally {
          button.disabled = false;
        }
      });
    });
  }

  // ============================================================
  // Hội nhóm: tạo nhóm, tham gia/rời nhóm, đăng bài trong nhóm
  // ============================================================

  function setCommunityJoinButton(btn, joined) {
    btn.classList.toggle('is-joined', joined);
    btn.classList.toggle('btn-outline', joined);
    btn.classList.toggle('btn-primary', !joined);
    btn.textContent = joined ? 'Đã tham gia' : 'Tham gia';
  }

  function initCommunityPages() {
    const page = document.querySelector('.community-page');
    if (!page) return;

    // ----- Tìm hội nhóm: LIKE %keyword% + ignore-case ở backend -----
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
        if (joined && !window.confirm('Bạn muốn rời khỏi hội nhóm này?')) return;
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

    // ----- Modal "Tạo hội nhóm" (trang danh sách) -----
    const openBtn = document.getElementById('openCreateCommunityBtn');
    const modal = document.getElementById('createCommunityModal');
    if (openBtn && modal) {
      const closeBtn = document.getElementById('closeCreateCommunityBtn');
      const form = document.getElementById('createCommunityForm');
      const errorEl = document.getElementById('createCommunityError');
      const submitBtn = document.getElementById('createCommunitySubmitBtn');
      const nameInput = document.getElementById('newCommunityName');
      const descInput = document.getElementById('newCommunityDescription');

      const openModal = () => { modal.hidden = false; if (nameInput) nameInput.focus(); };
      const closeModal = () => { modal.hidden = true; if (errorEl) errorEl.style.display = 'none'; };

      openBtn.addEventListener('click', openModal);
      if (closeBtn) closeBtn.addEventListener('click', closeModal);
      modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });
      if (page.dataset.communityEscapeBound !== '1') {
        document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !modal.hidden) closeModal(); });
        page.dataset.communityEscapeBound = '1';
      }

      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = nameInput.value.trim();
        if (!name) return;
        const checked = form.querySelector('input[name="newCommunityPrivacy"]:checked');
        submitBtn.disabled = true;
        if (errorEl) errorEl.style.display = 'none';
        try {
          const created = await api('/api/communities', {
            method: 'POST',
            body: {
              name: name,
              description: descInput.value.trim(),
              privacy: checked ? checked.value : 'PUBLIC'
            }
          });
          location.href = '/communities/' + created.id;
        } catch (err) {
          if (errorEl) {
            errorEl.textContent = err.message || 'Không thể tạo hội nhóm';
            errorEl.style.display = 'block';
          }
          submitBtn.disabled = false;
        }
      });
    }

    // ----- Nút Tham gia / Đã tham gia trong danh sách khám phá (optimistic) -----
    document.querySelectorAll('.btn-community-join').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (btn.dataset.busy === '1') return;
        const id = btn.dataset.communityId;
        const wasJoined = btn.classList.contains('is-joined');
        if (wasJoined && !window.confirm('Bạn muốn rời khỏi hội nhóm này?')) return;

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

    // ----- Trang chi tiết hội nhóm -----
    const communityId = page.dataset.communityId;
    if (!communityId) return;

    const joinBtn = document.getElementById('communityJoinBtn');
    if (joinBtn) {
      joinBtn.addEventListener('click', async () => {
        joinBtn.disabled = true;
        try {
          await api('/api/communities/' + communityId + '/join', { method: 'POST' });
          location.reload(); // tải lại để hiện ô đăng bài + (với nhóm riêng tư) mở khóa bài viết
        } catch (err) {
          toast(err.message || 'Không thể tham gia hội nhóm', true);
          joinBtn.disabled = false;
        }
      });
    }

    const leaveBtn = document.getElementById('communityLeaveBtn');
    if (leaveBtn) {
      leaveBtn.addEventListener('click', async () => {
        if (!window.confirm('Bạn muốn rời khỏi hội nhóm này?')) return;
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
          await showNewCommunityPost(communityId, created && created.id);
        } catch (err) {
          toast(err.message || 'Không thể đăng bài', true);
          composeBtn.disabled = false;
        }
      });
    }
  }

  /** Chèn bài vừa đăng lên đầu feed hội nhóm mà không phải tải lại cả trang. */
  async function showNewCommunityPost(communityId, postId) {
    const panel = document.getElementById('communityFeedPanel');
    try {
      const res = await fetch('/api/communities/' + communityId + '/posts/fragment?page=0', { credentials: 'same-origin' });
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
      formatInitialCounts();
      toast('Đã đăng bài');
    } catch (e) {
      location.reload();
    }
  }

  // ============================================================
  // FIRST-PAINT OPTIMIZATION
  // - Badge unread is deliberately not part of server-side page render.
  // - Sidebar navigation prefetches HTML on hover and can swap the body
  //   without a full-document reload for non-chat pages.
  // ============================================================
  function setBadge(elId, count) {
    const el = document.getElementById(elId);
    if (!el) return;
    const n = Math.max(0, Number(count) || 0);
    el.textContent = n > 99 ? '99+' : String(n);
    el.style.display = n > 0 ? '' : 'none';
  }

  let navBadgeRefreshAt = 0;
  let navBadgeRequest = null;

  function refreshNavBadges(force) {
    if (!document.body.dataset.currentUserId) return;
    const now = Date.now();
    if (!force && navBadgeRequest) return;
    if (!force && now - navBadgeRefreshAt < 5000) return;

    navBadgeRequest = Promise.allSettled([
      api('/api/notifications/unread-count', { method: 'GET' }),
      api('/api/chat/unread-count', { method: 'GET' })
    ]).then(function (results) {
      if (results[0].status === 'fulfilled') setBadge('notifUnreadBadge', results[0].value?.count ?? results[0].value ?? 0);
      if (results[1].status === 'fulfilled') setBadge('chatUnreadBadge', results[1].value?.count ?? results[1].value ?? 0);
      navBadgeRefreshAt = Date.now();
    }).finally(function () {
      navBadgeRequest = null;
    });
  }


  const softPageCache = new Map();
  const SOFT_NAV_ROUTES = ['/feed', '/compose', '/search', '/activity', '/insights', '/saved', '/profile/me', '/communities'];
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

  function prefetchPage(url) {
    const key = url.href;
    if (softPageCache.has(key)) return;
    fetch(key, { credentials: 'same-origin', headers: { 'X-ALOUTE-Navigation': 'prefetch' }, cache: 'no-store' })
      .then(function (res) {
        if (!res.ok || !res.headers.get('content-type')?.includes('text/html')) throw new Error('prefetch');
        return res.text();
      })
      .then(function (html) {
        softPageCache.set(key, { html: html, at: Date.now() });
        if (softPageCache.size > 8) softPageCache.delete(softPageCache.keys().next().value);
      })
      .catch(function () {});
  }

  async function fetchPageHtml(url) {
    const key = url.href;
    const cached = softPageCache.get(key);
    if (cached && Date.now() - cached.at < 30_000) return cached.html;
    const res = await fetch(key, { credentials: 'same-origin', headers: { 'X-ALOUTE-Navigation': 'soft' }, cache: 'no-store' });
    if (!res.ok || !res.headers.get('content-type')?.includes('text/html')) throw new Error('navigation');
    const html = await res.text();
    softPageCache.set(key, { html: html, at: Date.now() });
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
    initQuickComposeBox();
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
    initSidebarHoverState();
    initSidebarNavigation();
    refreshNavBadges();
  }

  function initSidebarHoverState() {
    // The rail itself stays 72px. Only .sidebar__panel owns the expandable
    // hit area; binding pointerleave to the fixed rail caused the rail to
    // close as soon as the pointer moved over the expanded panel.
    if (!window.matchMedia || !window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    document.querySelectorAll('.sidebar.sidebar--feed-collapsed, .sidebar.sidebar--chat-collapsed').forEach(function (sidebar) {
      if (sidebar.dataset.hoverStateBound === '2') return;
      sidebar.dataset.hoverStateBound = '2';
      const panel = sidebar.querySelector('.sidebar__panel');
      if (!panel) return;
      let closeTimer = null;

      const open = function () {
        if (closeTimer) { clearTimeout(closeTimer); closeTimer = null; }
        sidebar.classList.add('is-expanded');
      };

      const scheduleClose = function () {
        if (closeTimer) clearTimeout(closeTimer);
        closeTimer = setTimeout(function () {
          sidebar.classList.remove('is-expanded');
          closeTimer = null;
        }, 90);
      };

      panel.addEventListener('pointerenter', open);
      panel.addEventListener('pointerleave', scheduleClose);
      sidebar.addEventListener('focusin', open);
      sidebar.addEventListener('focusout', function (e) {
        if (!sidebar.contains(e.relatedTarget)) scheduleClose();
      });
    });
  }

  function initSidebarNavigation() {
    const links = document.querySelectorAll('.sidebar a[href]');
    links.forEach(function (link) {
      if (link.dataset.softNavBound === '1') return;
      const raw = link.getAttribute('href');
      if (!raw || raw === '#' || link.id === 'logoutBtn') return;
      let url;
      try { url = new URL(raw, window.location.origin); } catch (_) { return; }
      if (!canSoftNavigate(url)) return;
      link.dataset.softNavBound = '1';
      link.addEventListener('pointerenter', function () { prefetchPage(url); }, { passive: true });
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
    // Cho browser paint giao diện trước, sau đó mới dựng realtime + badge.
    runPageInitializers();
    const userId = document.body.dataset.currentUserId;
    if (userId) {
      const startRealtime = function () { connectRealtime(); };
      if (window.requestIdleCallback) requestIdleCallback(startRealtime, { timeout: 700 });
      else setTimeout(startRealtime, 180);
    }
  });

  return {
    api, toast, timeAgo, escapeHtml, formatCount,
    onRealtimeReady, connectRealtime,
    initFeedInteractions, initFollowButtons, initCloseFriendButtons
  };
})();