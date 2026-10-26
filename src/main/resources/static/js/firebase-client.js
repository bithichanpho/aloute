(function () {
  'use strict';

  // Load Firebase on auth/push setup pages, and on pages where this browser has opted into push.
  // FCM uses Firebase Installation IDs (FIDs), not the deprecated getToken() API.
  const FIREBASE_SDK_VERSION = '13.0.0';
  const FIREBASE_CDN = 'https://www.gstatic.com/firebasejs/' + FIREBASE_SDK_VERSION + '/';
  const googleButton = document.getElementById('googleSignInBtn');
  const googleDivider = document.getElementById('googleSignInDivider');
  const googleStatus = document.getElementById('googleSignInStatus');
  const pushButton = document.getElementById('enablePushNotificationsBtn');
  const pushStatus = document.getElementById('pushNotificationStatus');
  const pushFidStorageKey = 'aloute-fcm-fid';
  const pushOptOutKey = 'aloute-fcm-disabled';
  const currentUserId = document.body.dataset.currentUserId || '';
  let configPromise;
  let appSdkPromise;
  let authSdkPromise;
  let messagingSdkPromise;
  let firebaseApp;
  let messagingSdk;
  let messagingInstance;
  let pendingFidRegistration = null;
  let messagingListenersReady = false;
  const hasExistingPushEnrollment = Boolean(
    currentUserId && storageGet(pushFidStorageKey) && storageGet(pushOptOutKey) !== 'true'
  );

  async function getGoogleReauthenticationToken() {
    const config = await getConfig();
    if (!config.googleLoginEnabled) {
      throw new Error('Xác minh Google chưa được cấu hình. Hãy nhập mật khẩu hiện tại.');
    }
    const [app, authSdk] = await Promise.all([getFirebaseApp(config), getAuthSdk()]);
    const auth = authSdk.getAuth(app);
    const provider = new authSdk.GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });
    // Reauthenticate the existing Firebase user when available so selecting another Google
    // account cannot silently replace the browser's Firebase identity. If the app's Firebase
    // session was cleared, allow an interactive sign-in; the server still checks account email.
    const currentUser = auth.currentUser;
    const result = currentUser
      ? await authSdk.reauthenticateWithPopup(currentUser, provider)
      : await authSdk.signInWithPopup(auth, provider);
    if (!result.user) throw new Error('Google không trả về tài khoản.');
    if (currentUser && result.user.uid !== currentUser.uid) {
      throw new Error('Tài khoản Google xác minh không khớp phiên hiện tại.');
    }
    return result.user.getIdToken(true);
  }

  window.AlouteFirebase = window.AlouteFirebase || {};
  window.AlouteFirebase.getGoogleReauthenticationToken = getGoogleReauthenticationToken;

  // The Push control only exists on /activity, but an enrolled browser can be on
  // any ALOUTE page. Initialize foreground FCM handling there too, so FCM can
  // serve as a fallback toast if the WebSocket/STOMP connection is disconnected.
  if (!googleButton && !pushButton && !hasExistingPushEnrollment) return;


  function csrfHeaders(headers, method) {
    if (window.Aloute && typeof window.Aloute.csrfHeaders === 'function') {
      return window.Aloute.csrfHeaders(headers, method);
    }
    const result = Object.assign({}, headers || {});
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(String(method || 'GET').toUpperCase())) {
      const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
      if (match) {
        try { result['X-XSRF-TOKEN'] = decodeURIComponent(match[1]); }
        catch (_) { result['X-XSRF-TOKEN'] = match[1]; }
      }
    }
    return result;
  }

  function setStatus(element, message, isError) {
    if (!element) return;
    element.textContent = message || '';
    element.classList.toggle('text-danger', Boolean(isError));
  }

  function storageGet(key) {
    try { return localStorage.getItem(key); } catch (ignored) { return null; }
  }

  function storageSet(key, value) {
    try { localStorage.setItem(key, value); } catch (ignored) { /* Storage may be restricted. */ }
  }

  function storageRemove(key) {
    try { localStorage.removeItem(key); } catch (ignored) { /* Storage may be restricted. */ }
  }

  function importFirebase(file) {
    const url = FIREBASE_CDN + file;
    return import(url).catch((cause) => {
      // A CSP that allows only 'self' blocks these official browser-module imports.
      // Keep the underlying error in DevTools without returning internals to the UI.
      console.error('[ALOUTE] Firebase SDK module could not be loaded:', url, cause);
      const error = new Error(
        'Không tải được Firebase SDK từ Google CDN. Hãy kiểm tra mạng/trình chặn nội dung và Content-Security-Policy có cho phép https://www.gstatic.com trong script-src.'
      );
      error.code = 'aloute/firebase-sdk-load-failed';
      error.cause = cause;
      throw error;
    });
  }

  function getAppSdk() {
    if (!appSdkPromise) {
      appSdkPromise = importFirebase('firebase-app.js').catch((error) => {
        appSdkPromise = null; // Let a later user retry after a transient network failure.
        throw error;
      });
    }
    return appSdkPromise;
  }

  function getAuthSdk() {
    if (!authSdkPromise) {
      authSdkPromise = importFirebase('firebase-auth.js').catch((error) => {
        authSdkPromise = null; // A rejected promise must not permanently poison this page.
        throw error;
      });
    }
    return authSdkPromise;
  }

  function getMessagingSdk() {
    if (!messagingSdkPromise) {
      messagingSdkPromise = importFirebase('firebase-messaging.js').catch((error) => {
        messagingSdkPromise = null;
        throw error;
      });
    }
    return messagingSdkPromise;
  }

  async function getFirebaseApp(config) {
    const sdk = await getAppSdk();
    if (firebaseApp) return firebaseApp;
    firebaseApp = sdk.getApps().length ? sdk.getApp() : sdk.initializeApp(config.web);
    return firebaseApp;
  }

  async function getConfig() {
    if (!configPromise) {
      configPromise = fetch('/api/firebase/config', { credentials: 'same-origin' })
        .then(async (response) => {
          const payload = await response.json().catch(() => null);
          if (!response.ok || !payload || payload.success === false) {
            throw new Error((payload && payload.message) || 'Không đọc được cấu hình Firebase.');
          }
          return payload.data;
        });
    }
    return configPromise;
  }

  async function googleSignIn() {
    if (!googleButton || googleButton.disabled) return;
    googleButton.disabled = true;
    googleButton.setAttribute('aria-busy', 'true');
    setStatus(googleStatus, 'Đang kết nối Google...', false);
    try {
      const config = await getConfig();
      if (!config.googleLoginEnabled) {
        throw new Error('Firebase chưa được cấu hình đầy đủ ở backend. Hãy làm theo FIREBASE_SETUP.md.');
      }
      const [app, authSdk] = await Promise.all([getFirebaseApp(config), getAuthSdk()]);
      const auth = authSdk.getAuth(app);
      const provider = new authSdk.GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      const result = await authSdk.signInWithPopup(auth, provider);
      if (!result.user) throw new Error('Google không trả về tài khoản.');
      const idToken = await result.user.getIdToken(true);
      let code = '';
      let payload = null;
      for (let attempt = 0; attempt < 3; attempt += 1) {
        const response = await fetch('/api/auth/firebase/login', {
          method: 'POST',
          credentials: 'same-origin',
          headers: csrfHeaders({ 'Content-Type': 'application/json' }, 'POST'),
          body: JSON.stringify({ idToken, code })
        });
        payload = await response.json().catch(() => null);
        if (response.ok && payload && payload.success !== false) break;
        const message = (payload && payload.message) || '';
        // Account has 2FA enabled: ask for the TOTP / recovery code and retry with the same idToken.
        if (response.status === 401 && message.includes('xác thực hai lớp') && attempt < 2) {
          const entered = window.prompt('Tài khoản đã bật xác thực hai lớp. Nhập mã Authenticator hoặc mã khôi phục:');
          if (!entered || !entered.trim()) throw new Error('Cần mã xác thực hai lớp để đăng nhập bằng Google.');
          code = entered.trim();
          continue;
        }
        if (response.status === 401 && message.includes('2FA') && attempt < 2) {
          const entered = window.prompt('Mã không hợp lệ. Nhập lại mã Authenticator hoặc mã khôi phục:');
          if (!entered || !entered.trim()) throw new Error(message);
          code = entered.trim();
          continue;
        }
        throw new Error(message || 'Không thể đăng nhập vào ALOUTE bằng Google.');
      }
      window.location.assign('/feed');
    } catch (error) {
      const code = error && error.code;
      let message = error && error.message ? error.message : 'Đăng nhập Google thất bại.';
      if (code === 'auth/popup-closed-by-user') message = 'Bạn đã đóng cửa sổ đăng nhập Google.';
      if (code === 'auth/unauthorized-domain') message = 'Tên miền chưa được thêm vào Authorized domains trong Firebase Authentication.';
      if (code === 'auth/popup-blocked') message = 'Trình duyệt đã chặn cửa sổ đăng nhập. Hãy cho phép popup rồi thử lại.';
      setStatus(googleStatus, message, true);
      googleButton.disabled = false;
      googleButton.removeAttribute('aria-busy');
    }
  }

  async function pushApi(path, method, fid) {
    const response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: csrfHeaders({ 'Content-Type': 'application/json' }, method),
      body: JSON.stringify({ fid })
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload || payload.success === false) {
      throw new Error((payload && payload.message) || 'Không thể cập nhật cài đặt thông báo đẩy.');
    }
    return payload.data;
  }

  function settlePendingFidRegistration(error, fid) {
    if (!pendingFidRegistration) return;
    const pending = pendingFidRegistration;
    pendingFidRegistration = null;
    window.clearTimeout(pending.timeoutId);
    if (error) pending.reject(error);
    else pending.resolve(fid);
  }

  async function initializeMessaging(config) {
    if (messagingInstance) return messagingInstance;
    const [app, sdk] = await Promise.all([getFirebaseApp(config), getMessagingSdk()]);
    if (!(await sdk.isSupported())) {
      throw new Error('Trình duyệt này chưa hỗ trợ Firebase Cloud Messaging.');
    }
    messagingSdk = sdk;
    messagingInstance = sdk.getMessaging(app);

    if (!messagingListenersReady) {
      messagingListenersReady = true;
      sdk.onRegistered(messagingInstance, async (fid) => {
        // Ignore an unexpected re-registration when the user explicitly disabled push.
        if (!pendingFidRegistration && storageGet(pushOptOutKey) === 'true') return;
        try {
          await pushApi('/api/push/register', 'POST', fid);
          storageSet(pushFidStorageKey, fid);
          storageRemove(pushOptOutKey);
          setPushButtonState(true);
          if (pushStatus) setStatus(pushStatus, 'Thông báo đẩy đã bật trên thiết bị này.', false);
          settlePendingFidRegistration(null, fid);
        } catch (error) {
          settlePendingFidRegistration(error || new Error('Không lưu được Firebase Installation ID.'));
          if (!pendingFidRegistration) {
            setStatus(pushStatus, error.message || 'Không lưu được cấu hình thông báo đẩy.', true);
          }
        }
      });

      sdk.onUnregistered(messagingInstance, async (fid) => {
        try { await pushApi('/api/push/register', 'DELETE', fid); } catch (ignored) { /* Idempotent cleanup. */ }
        if (storageGet(pushFidStorageKey) === fid) storageRemove(pushFidStorageKey);
        setPushButtonState(false);
      });

      sdk.onMessage(messagingInstance, (payload) => {
        const data = payload && payload.data ? payload.data : {};
        // WebSocket is the primary foreground channel. Use FCM as a fallback when
        // the STOMP connection is down, while avoiding duplicate chat toasts.
        if (data.type === 'CHAT_MESSAGE' && window.__alouteRealtimeConnected === true) return;
        if (window.Aloute && typeof window.Aloute.toast === 'function') {
          const title = data.title || (data.type === 'CHAT_MESSAGE' ? 'Tin nhắn mới' : 'ALOUTE');
          const body = data.body || 'Bạn có thông báo mới.';
          window.Aloute.toast(data.type === 'CHAT_MESSAGE' ? title + ': ' + body : body);
        }
      });
    }
    return messagingInstance;
  }

  async function registerFirebaseInstallation(config, registration) {
    await initializeMessaging(config);
    if (pendingFidRegistration) {
      settlePendingFidRegistration(new Error('Đang đăng ký thông báo, vui lòng đợi một chút.'));
    }
    const fidPromise = new Promise((resolve, reject) => {
      pendingFidRegistration = {
        resolve,
        reject,
        timeoutId: window.setTimeout(() => {
          settlePendingFidRegistration(new Error('Firebase chưa trả về Installation ID. Hãy thử lại sau ít giây.'));
        }, 20000)
      };
    });
    try {
      Promise.resolve(messagingSdk.register(messagingInstance, {
        vapidKey: config.vapidKey,
        serviceWorkerRegistration: registration
      })).catch((error) => settlePendingFidRegistration(error));
    } catch (error) {
      settlePendingFidRegistration(error);
    }
    return fidPromise;
  }

  async function enablePush(config) {
    if (!('Notification' in window)) throw new Error('Trình duyệt này không hỗ trợ thông báo.');
    if (!('serviceWorker' in navigator)) throw new Error('Trình duyệt này không hỗ trợ service worker.');
    if (!config.messagingEnabled || !config.vapidKey) {
      throw new Error('Chưa cấu hình VAPID key và Firebase Admin ở Firebase/backend.');
    }
    if (Notification.permission === 'denied') {
      throw new Error('Bạn đã chặn thông báo cho trang này. Hãy cho phép thông báo trong cài đặt trình duyệt.');
    }

    let permission = Notification.permission;
    if (permission !== 'granted') permission = await Notification.requestPermission();
    if (permission !== 'granted') throw new Error('Bạn chưa cấp quyền nhận thông báo.');

    const registration = await navigator.serviceWorker.register('/firebase-messaging-sw.js');
    storageRemove(pushOptOutKey);
    const fid = await registerFirebaseInstallation(config, registration);
    setPushButtonState(true);
    setStatus(pushStatus, 'Đã bật thông báo đẩy trên thiết bị này.', false);
    if (window.Aloute && typeof window.Aloute.toast === 'function') {
      window.Aloute.toast('Đã bật thông báo đẩy.');
    }
    return fid;
  }

  async function disablePush() {
    const fid = storageGet(pushFidStorageKey);
    if (fid) await pushApi('/api/push/register', 'DELETE', fid);
    storageRemove(pushFidStorageKey);
    storageSet(pushOptOutKey, 'true');
    if (messagingSdk && messagingInstance) {
      try { await messagingSdk.unregister(messagingInstance); } catch (ignored) { /* May already be unregistered. */ }
    }
    setPushButtonState(false);
    setStatus(pushStatus, 'Đã tắt thông báo đẩy trên thiết bị này.', false);
  }

  function setPushButtonState(enabled) {
    if (!pushButton) return;
    pushButton.innerHTML = enabled
      ? '<i class="bi bi-bell-slash" aria-hidden="true"></i> Tắt thông báo đẩy'
      : '<i class="bi bi-bell" aria-hidden="true"></i> Bật thông báo đẩy';
    pushButton.dataset.enabled = enabled ? 'true' : 'false';
  }

  async function handlePushButton() {
    if (!pushButton || pushButton.disabled) return;
    pushButton.disabled = true;
    try {
      if (pushButton.dataset.enabled === 'true') {
        await disablePush();
      } else {
        setStatus(pushStatus, 'Đang bật thông báo...', false);
        const config = await getConfig();
        await enablePush(config);
      }
    } catch (error) {
      setStatus(pushStatus, error && error.message ? error.message : 'Không thể bật thông báo đẩy.', true);
    } finally {
      pushButton.disabled = false;
    }
  }

  async function bootstrap() {
    try {
      const config = await getConfig();
      if (googleButton) {
        if (config.googleLoginEnabled) {
          googleButton.hidden = false;
          if (googleDivider) googleDivider.hidden = false;
          googleButton.addEventListener('click', googleSignIn);
        } else {
          googleButton.hidden = true;
          if (googleDivider) googleDivider.hidden = true;
          if (config.webEnabled) {
            setStatus(googleStatus, 'Cần cấu hình Firebase Admin credentials ở backend để bật đăng nhập Google.', false);
          }
        }
      }

      if (pushButton) {
        if (config.messagingEnabled && config.vapidKey && document.body.dataset.currentUserId) {
          pushButton.hidden = false;
          const optedOut = storageGet(pushOptOutKey) === 'true';
          let hasFid = Boolean(storageGet(pushFidStorageKey));
          if (Notification.permission === 'granted' && !optedOut) {
            try {
              const registration = await navigator.serviceWorker.register('/firebase-messaging-sw.js');
              await registerFirebaseInstallation(config, registration);
              hasFid = Boolean(storageGet(pushFidStorageKey));
            } catch (ignored) {
              // Keep the control available; user can retry explicitly if background refresh fails.
            }
          }
          setPushButtonState(hasFid && !optedOut);
          if (Notification.permission === 'granted' && hasFid && !optedOut) {
            setStatus(pushStatus, 'Thông báo đẩy đã bật trên thiết bị này.', false);
          } else {
            setStatus(pushStatus, 'Bật để nhận thông báo khi không mở ALOUTE.', false);
          }
          pushButton.addEventListener('click', handlePushButton);
        } else {
          pushButton.hidden = true;
          if (config.webEnabled && config.vapidKey && !config.messagingEnabled) {
            setStatus(pushStatus, 'Cần cấu hình Firebase Admin credentials ở backend để gửi FCM.', false);
          }
        }
      } else if (hasExistingPushEnrollment && config.messagingEnabled && config.vapidKey) {
        // Push was enabled earlier from /activity. Reattach the foreground listener
        // on feed/chat/profile pages without prompting the user again.
        try {
          await navigator.serviceWorker.register('/firebase-messaging-sw.js');
          await initializeMessaging(config);
        } catch (error) {
          console.debug('[ALOUTE] Foreground FCM listener unavailable on this page.', error);
        }
      }
    } catch (error) {
      if (googleButton) {
        googleButton.hidden = true;
        if (googleDivider) googleDivider.hidden = true;
        setStatus(googleStatus, error.message || 'Không thể khởi tạo Firebase.', true);
      }
      if (pushButton) {
        pushButton.hidden = true;
        setStatus(pushStatus, error.message || 'Không thể khởi tạo thông báo đẩy.', true);
      }
    }
  }

  bootstrap();
})();
