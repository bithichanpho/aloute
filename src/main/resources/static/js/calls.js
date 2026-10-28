// Shared voice/video calls for the inbox and floating conversations.
(function () {
  if (window.AlouteCalls) return;
  const currentUserId = document.body.dataset.currentUserId;
  let stompClientRef = null;
  const pendingCallHangups = new Map();

  let peerConnection = null;
  let localStream = null;
  let activeCallId = null;
  let callPeerUserId = null;
  let pendingIncomingOffer = null;
  let activeCallType = 'video';
  let outgoingCallTimer = null;
  let incomingCallTimer = null;
  const CALL_TIMEOUT_MS = 30 * 1000;
  // STUN-only fallback can work on simple networks, but TURN is required for reliable
  // calls across restrictive NATs/firewalls. Production TURN credentials come from the server.
  const fallbackIceServers = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ];
  let cachedIceServers = null;
  let cachedIceServersUntil = 0;
  let connectionRecoveryTimer = null;
  let warnedAboutLocalTurnUrl = false;
  const flushingIceConnections = new WeakSet();
  const CONNECTION_RECOVERY_GRACE_MS = 15000;
  const MAX_PENDING_ICE_CANDIDATES = 256;
  // Store candidates with their call ID: candidates may arrive before the user accepts an offer.
  const pendingIceCandidates = [];

  async function getRtcConfiguration() {
    if (cachedIceServers && cachedIceServersUntil > Date.now()) {
      return { iceServers: cachedIceServers, iceTransportPolicy: 'all' };
    }
    try {
      const timeoutSignal = typeof AbortSignal !== 'undefined' && AbortSignal.timeout
        ? AbortSignal.timeout(6000)
        : undefined;
      const servers = await Aloute.api('/api/webrtc/ice-servers', timeoutSignal ? { signal: timeoutSignal } : {});
      const usableServers = Array.isArray(servers)
        ? servers.filter(server => server && server.urls && (!Array.isArray(server.urls) || server.urls.length > 0))
        : [];
      if (usableServers.length) {
        cachedIceServers = usableServers;
        // The backend issues short-lived TURN REST credentials; refresh every five minutes.
        // Keep a safety margin for the minimum 5-minute TURN credential lifetime.
        cachedIceServersUntil = Date.now() + 4 * 60 * 1000;
        const hasTurn = usableServers.some(server => {
          const urls = Array.isArray(server.urls) ? server.urls : [server.urls];
          return urls.some(url => typeof url === 'string' && /^turns?:/i.test(url));
        });
        if (!hasTurn) {
          console.info('No TURN server configured. This same-LAN profile expects direct ICE; if devices cannot connect because of Wi-Fi client isolation or firewall rules, a reachable TURN server is required.');
        }
        const localPage = ['localhost', '127.0.0.1', '::1'].includes(String(window.location.hostname || '').toLowerCase());
        const localTurnUrl = usableServers.some(server => {
          const urls = Array.isArray(server.urls) ? server.urls : [server.urls];
          return urls.some(url => typeof url === 'string' && /^turns?:/i.test(url)
            && /@?(localhost|127\.0\.0\.1|\[::1\])(?=[:/?]|$)/i.test(url));
        });
        if (!localPage && localTurnUrl) {
          console.error('TURN is configured with localhost/loopback, which points to each visitor device. Set TURN_PUBLIC_HOST and TURN_EXTERNAL_IP to the public server address.');
          if (!warnedAboutLocalTurnUrl) {
            warnedAboutLocalTurnUrl = true;
            Aloute.toast('Cấu hình gọi chưa đúng: máy chủ TURN đang trỏ tới localhost. Hãy cấu hình TURN_PUBLIC_HOST và TURN_EXTERNAL_IP trên máy chủ rồi khởi động lại dịch vụ.', true);
          }
        }
        return { iceServers: cachedIceServers, iceTransportPolicy: 'all' };
      }
      throw new Error('API returned no usable ICE servers');
    } catch (error) {
      console.warn('Could not load ICE server configuration; temporarily using STUN-only fallback.', error);
    }
    cachedIceServers = fallbackIceServers;
    // Keep fallback caching short so the next call retries the authenticated ICE API quickly.
    cachedIceServersUntil = Date.now() + 10 * 1000;
    return { iceServers: fallbackIceServers, iceTransportPolicy: 'all' };
  }

  function clearPendingIceCandidates(keepCallId) {
    if (!keepCallId) {
      pendingIceCandidates.length = 0;
      return;
    }
    for (let i = pendingIceCandidates.length - 1; i >= 0; i--) {
      if (pendingIceCandidates[i].callId !== keepCallId) pendingIceCandidates.splice(i, 1);
    }
  }

  async function flushPendingIceCandidates(connection, callId) {
    if (!connection || connection !== peerConnection || !connection.remoteDescription || flushingIceConnections.has(connection)) return;
    flushingIceConnections.add(connection);
    try {
      while (connection === peerConnection && connection.remoteDescription) {
        const index = pendingIceCandidates.findIndex(item => item.callId === callId);
        if (index < 0) break;
        const item = pendingIceCandidates.splice(index, 1)[0];
        try {
          await connection.addIceCandidate(item.candidate);
        } catch (error) {
          // A candidate can become obsolete while ICE gathering continues; log instead of
          // silently swallowing it, and continue processing other candidates.
          console.warn('Could not add remote ICE candidate for call', callId, error);
        }
      }
    } finally {
      flushingIceConnections.delete(connection);
    }
  }

  function useRealtimeClient(client) {
    if (!client) return;
    stompClientRef = client;
    for (const [callId, payload] of pendingCallHangups) {
      try {
        signalCall('/app/call.hangup', payload);
        pendingCallHangups.delete(callId);
      } catch (_) { break; }
    }
  }
  Aloute.onRealtimeReady(useRealtimeClient);
  document.addEventListener('aloute:realtime-ready', event => useRealtimeClient(event.detail?.client));

  function formatCallDuration(rawSeconds) {
    const seconds = Math.max(0, Math.floor(Number(rawSeconds) || 0));
    const minutes = Math.floor(seconds / 60);
    const remainder = seconds % 60;
    if (minutes > 0) return minutes + ' phút' + (remainder > 0 ? ' ' + remainder + ' giây' : '');
    return seconds + ' giây';
  }

  function renderCallMessage(msg, mine, clock) {
    const isAudioCall = String(msg.callType || '').toLowerCase() === 'audio';
    const callStatus = String(msg.callStatus || '').toUpperCase();
    const isMissed = callStatus === 'MISSED';
    const isDeclined = callStatus === 'DECLINED';
    const isCancelled = callStatus === 'CANCELLED';
    const isEnded = callStatus === 'ENDED';
    const callLabel = isAudioCall ? 'Cuộc gọi thoại' : 'Cuộc gọi video';
    let title = callLabel;
    let detail = 'Đã kết thúc';
    if (isMissed) {
      title = mine ? (callLabel + ' · Không được trả lời') : (callLabel + ' nhỡ');
      detail = mine ? 'Không có phản hồi' : 'Cuộc gọi đến không được trả lời';
    } else if (isDeclined) {
      title = mine ? (callLabel + ' · Bị từ chối') : 'Bạn đã từ chối cuộc gọi';
      detail = 'Không kết nối';
    } else if (isCancelled) {
      title = callLabel + ' · Đã hủy';
      detail = 'Kết thúc trước khi trả lời';
    } else if (isEnded) {
      detail = msg.callDurationSeconds == null
        ? 'Đã kết thúc'
        : 'Thời lượng ' + formatCallDuration(msg.callDurationSeconds);
    }
    const icon = isAudioCall
      ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.8 19.8 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.12.9.33 1.77.62 2.61a2 2 0 0 1-.45 2.11L8 9.73a16 16 0 0 0 6 6l1.29-1.29a2 2 0 0 1 2.11-.45c.84.29 1.71.5 2.61.62A2 2 0 0 1 22 16.92Z"/></svg>'
      : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m23 7-7 5 7 5V7Z"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>';
    const callAgainTarget = mine ? msg.receiverId : msg.senderId;
    const canCallAgain = !!callAgainTarget && (isMissed || isDeclined || isCancelled || isEnded || !callStatus);
    const callAgainAttrs = canCallAgain
      ? ' data-call-again="' + Aloute.escapeHtml(String(callAgainTarget)) + '" data-call-type="' + (isAudioCall ? 'audio' : 'video') + '" role="button" tabindex="0" aria-label="Gọi lại" title="Nhấn để gọi lại"'
      : '';
    const callCardClass = (isMissed || isDeclined || isCancelled) ? 'bubble__call--missed' : 'bubble__call--completed';
    return '<div class="bubble__call ' + callCardClass + '"' + callAgainAttrs + '>' +
      '<span class="bubble__call-icon">' + icon + '</span>' +
      '<span class="bubble__call-body"><strong>' + title + '</strong><span>' + clock + ' · ' + detail + '</span></span>' +
    '</div>';
  }

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
    if (!stompClientRef || window.__alouteRealtimeConnected !== true) {
      throw new Error('Kết nối thời gian thực bị ngắt. Hãy tải lại trang và thử gọi lại.');
    }
    stompClientRef.send(destination, {}, JSON.stringify(payload));
  }

  async function createPeerConnection(targetUserId, callId) {
    if (peerConnection) {
      const previousConnection = peerConnection;
      peerConnection = null;
      previousConnection.close();
    }
    if (connectionRecoveryTimer) clearTimeout(connectionRecoveryTimer);
    connectionRecoveryTimer = null;

    const resolvedCallId = callId || activeCallId || ('call-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8));
    activeCallId = resolvedCallId;
    clearPendingIceCandidates(resolvedCallId);
    const rtcConfig = await getRtcConfiguration();
    if (activeCallId !== resolvedCallId) throw new Error('Cuộc gọi đã kết thúc');
    const connection = new RTCPeerConnection(rtcConfig);
    peerConnection = connection;

    // The server registers a call when it receives the offer. Buffer local candidates until
    // the offer/answer signaling frame has been sent, otherwise the server may reject early ICE.
    let localIceSignalingReady = false;
    const queuedLocalIceCandidates = [];
    const sendLocalIceCandidate = candidate => {
      try {
        signalCall('/app/call.ice', {
          targetUserId: Number(targetUserId), callId: resolvedCallId, candidate
        });
      } catch (error) {
        console.error('Could not send local ICE candidate for call', resolvedCallId, error);
      }
    };
    connection.flushQueuedLocalIceCandidates = () => {
      localIceSignalingReady = true;
      while (queuedLocalIceCandidates.length) sendLocalIceCandidate(queuedLocalIceCandidates.shift());
    };
    connection.onicecandidate = event => {
      if (peerConnection !== connection || !event.candidate) return;
      if (!localIceSignalingReady) queuedLocalIceCandidates.push(event.candidate);
      else sendLocalIceCandidate(event.candidate);
    };
    connection.onicecandidateerror = event => {
      console.warn('WebRTC ICE candidate error', {
        callId: resolvedCallId, url: event.url, code: event.errorCode, text: event.errorText
      });
    };
    connection.ontrack = event => {
      if (peerConnection !== connection || !remoteVideo) return;
      if (event.streams && event.streams[0]) {
        remoteVideo.srcObject = event.streams[0];
        const playPromise = remoteVideo.play();
        if (playPromise && typeof playPromise.catch === 'function') {
          playPromise.catch(error => console.warn('Remote media autoplay was blocked', error));
        }
      }
    };
    connection.onconnectionstatechange = () => {
      if (peerConnection !== connection) return;
      const state = connection.connectionState;
      if (state === 'connected') {
        if (connectionRecoveryTimer) clearTimeout(connectionRecoveryTimer);
        connectionRecoveryTimer = null;
        videoCallStatus.textContent = 'Đang gọi';
        return;
      }
      if (state === 'disconnected' || state === 'failed') {
        videoCallStatus.textContent = state === 'failed'
          ? 'Kết nối gặp lỗi, đang chờ phục hồi...'
          : 'Mất kết nối tạm thời, đang thử khôi phục...';
        if (!connectionRecoveryTimer) {
          const graceMs = state === 'failed' ? 5000 : CONNECTION_RECOVERY_GRACE_MS;
          connectionRecoveryTimer = setTimeout(() => {
            connectionRecoveryTimer = null;
            if (peerConnection !== connection) return;
            if (connection.connectionState === 'connected') return;
            closeVideoCall(true);
            Aloute.toast('Kết nối cuộc gọi bị gián đoạn. Hãy kiểm tra mạng rồi gọi lại.', true);
          }, graceMs);
        }
        return;
      }
      if (state === 'connecting' || state === 'new') {
        videoCallStatus.textContent = 'Đang kết nối...';
      }
    };
    if (localStream) localStream.getTracks().forEach(track => connection.addTrack(track, localStream));
    return connection;
  }

  async function ensureLocalStream(callType, callId) {
    if (localStream) return localStream;
    const stream = await navigator.mediaDevices.getUserMedia({ video: callType !== 'audio', audio: true });
    if (activeCallId !== callId) {
      stream.getTracks().forEach(track => track.stop());
      throw new Error('Cuộc gọi đã kết thúc');
    }
    localStream = stream;
    if (localVideo) localVideo.srcObject = localStream;
    return localStream;
  }

  function clearCallTimers() {
    if (outgoingCallTimer) clearTimeout(outgoingCallTimer);
    if (incomingCallTimer) clearTimeout(incomingCallTimer);
    outgoingCallTimer = null;
    incomingCallTimer = null;
  }

  function updateControlLabel(button, label, disabled) {
    if (!button) return;
    const text = button.querySelector('span');
    if (text) text.textContent = label;
    else button.textContent = label;
    button.setAttribute('aria-pressed', String(disabled));
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
    updateControlLabel(toggleMicBtn, 'Tắt mic', false);
    updateControlLabel(toggleCameraBtn, 'Tắt camera', false);
  }

  async function startCall(targetUserId, targetName, callType, targetAvatar) {
    if (!targetUserId || String(targetUserId) === String(currentUserId)) return;

    // Browsers expose camera/microphone APIs only in secure contexts. `localhost`
    // is treated specially by browsers, but a LAN IP over plain HTTP is not.
    if (!window.isSecureContext) {
      Aloute.toast('Không thể gọi từ địa chỉ HTTP không bảo mật. Hãy mở Aloute bằng HTTPS trên cả hai máy (localhost chỉ được xem là an toàn trên chính máy đó).', true);
      return;
    }
    if (!window.RTCPeerConnection || !navigator.mediaDevices?.getUserMedia) {
      Aloute.toast('Trình duyệt không hỗ trợ WebRTC hoặc không cấp quyền camera/micro. Hãy thử Chrome/Edge mới và kiểm tra quyền thiết bị.', true);
      return;
    }
    if (!stompClientRef || window.__alouteRealtimeConnected !== true) {
      Aloute.toast('Kết nối gọi thời gian thực chưa sẵn sàng. Hãy kiểm tra kết nối mạng rồi tải lại trang.', true);
      return;
    }
    if (activeCallId) return;
    const callId = 'call-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
    try {
      clearCallTimers();
      // One call owns all asynchronous setup steps.
      clearPendingIceCandidates();
      activeCallId = callId;
      callPeerUserId = Number(targetUserId);
      configureCallUi(callType, targetName, targetAvatar || '/images/avatar_default.png', false);
      videoCallStatus.textContent = 'Đang mở ' + (callType === 'audio' ? 'micro...' : 'camera...');
      answerCallBtn.hidden = true;
      if (rejectCallBtn) rejectCallBtn.hidden = true;
      if (incomingCallProfile) incomingCallProfile.hidden = true;
      videoCallModal.hidden = false;

      await ensureLocalStream(activeCallType, activeCallId);
      const connection = await createPeerConnection(targetUserId, callId);
      const offer = await connection.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: callType !== 'audio' });
      await connection.setLocalDescription(offer);
      if (peerConnection !== connection) throw new Error('Cuộc gọi đã thay đổi trong lúc thiết lập kết nối');
      signalCall('/app/call.offer', {
        targetUserId: Number(targetUserId),
        callId,
        callerUserId: Number(currentUserId),
        sdp: offer,
        callType: activeCallType
      });
      connection.flushQueuedLocalIceCandidates();
      videoCallStatus.textContent = 'Đang chờ ' + (targetName || 'người nhận') + ' chấp nhận...';

      outgoingCallTimer = setTimeout(() => {
        if (activeCallId !== callId) return;
        closeVideoCall(false);
        Aloute.toast('Không có phản hồi sau 30 giây. Cuộc gọi đã kết thúc và được ghi nhận là cuộc gọi nhỡ.');
      }, CALL_TIMEOUT_MS + 500);
    } catch (err) {
      if (activeCallId !== callId) return;
      clearCallTimers();
      closeVideoCall(true);
      let reason = err && err.message ? err.message : 'lỗi không xác định';
      if (err && (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError')) {
        reason = 'Trình duyệt chưa được cấp quyền dùng camera/micro. Hãy bật quyền trong thanh địa chỉ rồi thử lại.';
      } else if (err && err.name === 'NotFoundError') {
        reason = 'Không tìm thấy camera hoặc micro phù hợp trên thiết bị.';
      } else if (err && err.name === 'NotReadableError') {
        reason = 'Camera/micro đang được ứng dụng khác sử dụng hoặc thiết bị không thể mở.';
      }
      Aloute.toast('Không thể bắt đầu cuộc gọi: ' + reason, true);
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
      await ensureLocalStream(activeCallType, offer.callId);
      if (activeCallId !== offer.callId) return;
      callPeerUserId = Number(offer.fromUserId);
      const connection = await createPeerConnection(offer.fromUserId, offer.callId);
      await connection.setRemoteDescription(new RTCSessionDescription(offer.sdp));
      await flushPendingIceCandidates(connection, offer.callId);
      const answer = await connection.createAnswer();
      await connection.setLocalDescription(answer);
      if (peerConnection !== connection) throw new Error('Cuộc gọi đã thay đổi trong lúc thiết lập kết nối');
      signalCall('/app/call.answer', { targetUserId: Number(offer.fromUserId), callId: offer.callId, callerUserId: Number(offer.callerUserId || offer.fromUserId), callType: activeCallType, sdp: answer });
      connection.flushQueuedLocalIceCandidates();
      answerCallBtn.hidden = true;
      if (rejectCallBtn) rejectCallBtn.hidden = true;
      videoCallStatus.textContent = 'Đang gọi';
    } catch (err) {
      if (activeCallId !== offer.callId) return;
      Aloute.toast('Không thể nhận cuộc gọi: ' + (err.message || ''), true);
      closeVideoCall(true);
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
      if (!signal.callId || activeCallId === signal.callId) return;
      if (activeCallId && activeCallId !== signal.callId) {
        console.warn('Rejecting a new call offer while another call is active.');
        try {
          signalCall('/app/call.hangup', {
            targetUserId: Number(signal.fromUserId),
            callId: signal.callId,
            callerUserId: Number(signal.callerUserId || signal.fromUserId),
            callType: signal.callType === 'audio' ? 'audio' : 'video',
            reason: 'rejected',
            missed: false
          });
        } catch (error) { console.warn('Could not reject overlapping call offer', error); }
        return;
      }
      clearCallTimers();
      activeCallId = signal.callId;
      callPeerUserId = Number(signal.fromUserId);
      clearPendingIceCandidates(signal.callId);
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

      incomingCallTimer = setTimeout(() => {
        if (!pendingIncomingOffer || pendingIncomingOffer.callId !== signal.callId) return;
        pendingIncomingOffer = null;
        closeVideoCall(false);
      }, CALL_TIMEOUT_MS + 500);
      return;
    }
    if (signal.type === 'answer') {
      if (!peerConnection || signal.callId !== activeCallId) return;
      const connection = peerConnection;
      await connection.setRemoteDescription(new RTCSessionDescription(signal.sdp));
      if (peerConnection !== connection) return;
      await flushPendingIceCandidates(connection, signal.callId);
      clearCallTimers();
      videoCallStatus.textContent = 'Đang kết nối...';
      return;
    }
    if (signal.type === 'ice') {
      if (!signal.callId || !signal.candidate) return;
      // Signals may arrive before the offer is accepted or before setRemoteDescription finishes.
      // Keep a bounded per-call queue instead of dropping candidates or swallowing errors.
      if (activeCallId && signal.callId !== activeCallId) return;
      if (!activeCallId && pendingIncomingOffer && signal.callId !== pendingIncomingOffer.callId) return;
      if (pendingIceCandidates.length >= MAX_PENDING_ICE_CANDIDATES) {
        pendingIceCandidates.shift();
        console.warn('ICE candidate queue reached its limit; discarded the oldest candidate.');
      }
      pendingIceCandidates.push({ callId: signal.callId, candidate: signal.candidate });
      if (peerConnection && signal.callId === activeCallId) {
        await flushPendingIceCandidates(peerConnection, signal.callId);
      }
      return;
    }
    if (signal.type === 'hangup') {
      if (!activeCallId || signal.callId !== activeCallId) return;
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
    if (connectionRecoveryTimer) clearTimeout(connectionRecoveryTimer);
    connectionRecoveryTimer = null;
    clearPendingIceCandidates();
    const peerUserId = callPeerUserId;
    if (sendHangup && peerUserId && activeCallId) {
      const payload = {
        targetUserId: Number(peerUserId),
        callId: activeCallId,
        callerUserId: Number(currentUserId),
        callType: activeCallType,
        reason: 'cancelled',
        missed: false
      };
      try { signalCall('/app/call.hangup', payload); }
      catch (_) {
        pendingCallHangups.set(payload.callId, payload);
        while (pendingCallHangups.size > 16) pendingCallHangups.delete(pendingCallHangups.keys().next().value);
      }
    }
    const connection = peerConnection;
    peerConnection = null;
    if (connection) {
      connection.ontrack = null;
      connection.onicecandidate = null;
      connection.onconnectionstatechange = null;
      connection.close();
    }
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
    const popup = card.closest('.friends-chat-window')?._alouteChatState;
    const targetName = popup?.name || document.getElementById('chatThreadName')?.textContent || 'Bạn bè';
    startCall(targetId, targetName, card.dataset.callType === 'audio' ? 'audio' : 'video', popup?.avatar);
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

  document.addEventListener('aloute:call-signal', e => {
    const callId = e.detail?.callId;
    handleCallSignal(e.detail).catch(error => {
      if (callId !== activeCallId) return;
      console.error('Call signaling handler failed', error);
      closeVideoCall(true);
      Aloute.toast('Tín hiệu cuộc gọi bị lỗi. Hãy thử gọi lại.', true);
    });
  });
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
    updateControlLabel(toggleMicBtn, track.enabled ? 'Tắt mic' : 'Bật mic', !track.enabled);
  });
  if (toggleCameraBtn) toggleCameraBtn.addEventListener('click', () => {
    if (!localStream) return;
    const track = localStream.getVideoTracks()[0];
    if (!track) return;
    track.enabled = !track.enabled;
    updateControlLabel(toggleCameraBtn, track.enabled ? 'Tắt camera' : 'Bật camera', !track.enabled);
  });

  // Soft navigation replaces the body; retain the call UI and its live media streams.
  document.addEventListener('aloute:navigated', () => {
    const replacement = document.getElementById('videoCallModal');
    if (replacement && replacement !== videoCallModal) replacement.replaceWith(videoCallModal);
    else if (videoCallModal && !videoCallModal.isConnected) document.body.appendChild(videoCallModal);
  });
  window.addEventListener('pagehide', () => closeVideoCall(true));
  window.AlouteCalls = { start: startCall, renderMessage: renderCallMessage, formatDuration: formatCallDuration };
})();
