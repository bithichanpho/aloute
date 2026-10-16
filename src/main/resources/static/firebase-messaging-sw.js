/* Firebase Cloud Messaging background handler for ALOUTE. */
importScripts('https://www.gstatic.com/firebasejs/13.0.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/13.0.0/firebase-messaging-compat.js');

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  const rawUrl = event.notification && event.notification.data ? event.notification.data.url : '/activity';
  let targetUrl = new URL('/activity', self.location.origin).href;
  try {
    const candidate = new URL(rawUrl || '/activity', self.location.origin);
    if (candidate.origin === self.location.origin) targetUrl = candidate.href;
  } catch (ignored) { /* Keep the safe same-origin fallback. */ }

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (clients) {
      for (const client of clients) {
        if (client.url.startsWith(self.location.origin) && 'focus' in client) {
          if ('navigate' in client) {
            return client.navigate(targetUrl).then(function (navigatedClient) {
              return (navigatedClient || client).focus();
            }).catch(function () { return client.focus(); });
          }
          return client.focus();
        }
      }
      return self.clients.openWindow(targetUrl);
    })
  );
});

fetch('/api/firebase/config', { credentials: 'same-origin' })
  .then(function (response) {
    if (!response.ok) throw new Error('Firebase config unavailable');
    return response.json();
  })
  .then(function (payload) {
    const config = payload && payload.data;
    if (!config || !config.messagingEnabled || !config.webEnabled) return;
    if (!firebase.apps.length) firebase.initializeApp(config.web);
    const messaging = firebase.messaging();
    messaging.onBackgroundMessage(function (payload) {
      const data = payload && payload.data ? payload.data : {};
      const title = data.title || 'ALOUTE';
      const options = {
        body: data.body || 'Bạn có thông báo mới.',
        icon: '/images/avatar_default.png',
        badge: '/images/avatar_default.png',
        data: { url: data.url || '/activity' },
        tag: data.type === 'CHAT_MESSAGE' ? ('aloute-chat-' + (data.url || '/chat')) : 'aloute-notification'
      };
      return self.registration.showNotification(title, options);
    });
  })
  .catch(function () {
    // Firebase is optional until configured; keep the service worker harmless otherwise.
  });
