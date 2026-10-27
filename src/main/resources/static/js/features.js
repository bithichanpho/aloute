(() => {
  'use strict';
  const api = (url, method = 'GET', body) => Aloute.api(url, { method, body });
  const currentUser = document.body.dataset.currentUserId;
  const reviewRetries = new Set();
  document.addEventListener('click', async event => {
    const trigger = event.target.closest('[data-moderation-retry]');
    if (!trigger) return;
    event.preventDefault();
    const id = trigger.dataset.reviewPostId;
    if (!id || reviewRetries.has(id)) return;
    reviewRetries.add(id);
    const buttons = [...document.querySelectorAll('[data-moderation-retry]')]
      .filter(node => node.dataset.reviewPostId === id);
    buttons.forEach(node => { node.disabled = true; });
    try {
      await api('/api/posts/' + encodeURIComponent(id) + '/moderation-retry', 'POST');
      location.reload();
    } catch (error) {
      Aloute.toast(error.message, true);
    } finally {
      reviewRetries.delete(id);
      buttons.forEach(node => { node.disabled = false; });
    }
  });
  const element = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    return node;
  };
  function button(label, action) {
    const node = element('button', label);
    node.type = 'button';
    node.addEventListener('click', async () => {
      node.disabled = true;
      try { await action(node); } catch (error) { Aloute.toast(error.message, true); }
      finally { node.disabled = false; }
    });
    return node;
  }
  function field(parent, label, type = 'text', value = '') {
    const wrapper = element('label', label);
    const input = element(type === 'textarea' ? 'textarea' : 'input');
    if (type !== 'textarea') input.type = type;
    input.value = value;
    wrapper.append(input); parent.append(wrapper);
    return input;
  }
  function dialog(title, build) {
    const modal = element('dialog', null, 'feature-dialog');
    modal.append(element('h2', title));
    build(modal);
    modal.append(button('Đóng', () => modal.close()));
    modal.addEventListener('close', () => modal.remove());
    document.body.append(modal); modal.showModal();
  }
  function report(type, id) {
    dialog('Báo cáo vi phạm', modal => {
      const reason = field(modal, 'Mô tả lý do', 'textarea'); reason.maxLength = 1000;
      modal.append(button('Gửi báo cáo', async () => {
        await api('/api/reports', 'POST', { targetType: type, targetId: String(id), reason: reason.value });
        modal.close(); Aloute.toast('Đã gửi báo cáo để quản trị viên xem xét');
      }));
    });
  }
  function linkTags(node) {
    if (node.dataset.tagsLinked) return;
    node.dataset.tagsLinked = 'true';
    const text = node.textContent;
    const pattern = /(?<![\p{L}\p{N}_])(?:(#)([\p{L}\p{N}_]{1,50})|(@)([\p{L}\p{N}_](?:[\p{L}\p{N}_.-]{0,48}[\p{L}\p{N}_])?))/gu;
    const fragment = document.createDocumentFragment();
    let end = 0;
    for (const match of text.matchAll(pattern)) {
      fragment.append(document.createTextNode(text.slice(end, match.index)));
      const link = element('a', match[0], 'feature-tag');
      link.href = match[3] === '@' ? Aloute.profileUrl(match[4], null) : '/search?q=' + encodeURIComponent(match[0]); fragment.append(link);
      end = match.index + match[0].length;
    }
    fragment.append(document.createTextNode(text.slice(end))); node.replaceChildren(fragment);
  }
  function editPost(id) {
    return api('/api/posts/' + id).then(post => dialog('Chỉnh sửa bài viết', modal => {
      const content = field(modal, 'Nội dung', 'textarea', post.content); content.maxLength = 2000;
      const label = element('label', 'Ai có thể xem?');
      const select = element('select');
      [['PUBLIC', 'Công khai'], ['FRIENDS', 'Bạn bè'], ['CLOSE_FRIENDS', 'Bạn thân'], ['ONLY_ME', 'Chỉ mình tôi']].forEach(([value, text]) => {
        const option = element('option', text); option.value = value; select.append(option);
      });
      select.value = post.visibility || 'PUBLIC'; label.append(select); modal.append(label);
      modal.append(button('Lưu thay đổi', async () => {
        await api('/api/posts/' + id, 'PUT', { content: content.value, imageUrl: post.imageUrl, videoUrl: post.videoUrl, visibility: select.value });
        location.reload();
      }));
    }));
  }
  function closePostMenus(except) {
    document.querySelectorAll('.post__menu.is-open').forEach(menu => {
      if (menu === except) return;
      menu.classList.remove('is-open');
      menu.querySelector('[data-post-menu-trigger]')?.setAttribute('aria-expanded', 'false');
    });
  }
  document.addEventListener('click', event => {
    const trigger = event.target.closest('[data-post-menu-trigger]');
    if (trigger) {
      event.preventDefault();
      const menu = trigger.closest('.post__menu');
      if (!menu) return;
      const shouldOpen = !menu.classList.contains('is-open');
      closePostMenus(menu);
      menu.classList.toggle('is-open', shouldOpen);
      trigger.setAttribute('aria-expanded', String(shouldOpen));
      return;
    }
    if (event.target.closest('.post__menu-item')) {
      closePostMenus();
      return;
    }
    if (!event.target.closest('.post__menu')) closePostMenus();
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    const openMenu = document.querySelector('.post__menu.is-open');
    if (!openMenu) return;
    const trigger = openMenu.querySelector('[data-post-menu-trigger]');
    closePostMenus();
    trigger?.focus();
  });

  function closeCommentMenus(except) {
    document.querySelectorAll('.comment-menu.is-open').forEach(menu => {
      if (menu === except) return;
      menu.classList.remove('is-open');
      menu.querySelector('[data-comment-menu-trigger]')?.setAttribute('aria-expanded', 'false');
    });
  }
  document.addEventListener('click', event => {
    const trigger = event.target.closest('[data-comment-menu-trigger]');
    if (trigger) {
      event.preventDefault();
      event.stopPropagation();
      const menu = trigger.closest('.comment-menu');
      if (!menu) return;
      const shouldOpen = !menu.classList.contains('is-open');
      closeCommentMenus(menu);
      menu.classList.toggle('is-open', shouldOpen);
      trigger.setAttribute('aria-expanded', String(shouldOpen));
      return;
    }
    if (event.target.closest('[data-comment-menu-item]')) {
      closeCommentMenus();
      return;
    }
    if (!event.target.closest('.comment-menu')) closeCommentMenus();
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    const openMenu = document.querySelector('.comment-menu.is-open');
    if (!openMenu) return;
    const trigger = openMenu.querySelector('[data-comment-menu-trigger]');
    closeCommentMenus();
    trigger?.focus();
  });

  function enhance() {
    document.querySelectorAll('.post__content').forEach(linkTags);
    if (!currentUser) return;
    document.querySelectorAll('.post[data-post-id]:not([data-features])').forEach(post => {
      post.dataset.features = 'true';
      const id = post.dataset.actionPostId || post.dataset.postId;
      const menu = post.querySelector('.post__menu-list');

      // Keep post management inside the top-right overflow menu instead of adding a second toolbar.
      if (menu && post.querySelector('.post__delete')) {
        menu.classList.add('has-owner-actions');
        const editButton = button('Chỉnh sửa bài viết', () => editPost(id));
        editButton.className = 'post__menu-item';
        editButton.dataset.postEdit = 'true';
        editButton.setAttribute('role', 'menuitem');
        const historyButton = button('Lịch sử chỉnh sửa', async () => {
          const history = await api('/api/posts/' + id + '/history');
          dialog('Lịch sử chỉnh sửa', modal => {
            if (!history.length) modal.append(element('p', 'Chưa có lần chỉnh sửa nào.'));
            history.forEach(item => { modal.append(element('h4', new Date(item.createdAt).toLocaleString()), element('p', item.content)); });
          });
        });
        historyButton.className = 'post__menu-item';
        historyButton.dataset.postHistory = 'true';
        historyButton.setAttribute('role', 'menuitem');
        menu.prepend(historyButton);
        menu.prepend(editButton);
      }

      if (menu && !menu.querySelector('[data-post-report]')) {
        const reportButton = button('Báo cáo', () => report('POST', id));
        reportButton.className = 'post__menu-item post__menu-item--report';
        reportButton.dataset.postReport = 'true';
        reportButton.setAttribute('role', 'menuitem');
        menu.append(reportButton);
      }

      // Saving belongs on the same interaction row as reaction, comment, repost and share.
      const actionRow = post.querySelector('.post__actions');
      if (actionRow && !actionRow.querySelector('.action--save')) {
        const saveButton = button('Lưu bài viết', async node => {
          const wasSaved = node.classList.contains('is-saved');
          await api('/api/posts/' + id + '/saved', wasSaved ? 'DELETE' : 'PUT');
          const nextSaved = !wasSaved;
          node.classList.toggle('is-saved', nextSaved);
          node.setAttribute('aria-pressed', String(nextSaved));
          const label = nextSaved ? 'Bỏ lưu bài viết' : 'Lưu bài viết';
          node.setAttribute('aria-label', label);
          node.title = label;
          Aloute.toast(nextSaved ? 'Đã lưu bài viết' : 'Đã bỏ lưu bài viết');
        });
        saveButton.className = 'action action--save';
        saveButton.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 4.75A1.75 1.75 0 0 1 7.75 3h8.5A1.75 1.75 0 0 1 18 4.75V21l-6-4-6 4V4.75Z"/></svg>';
        saveButton.setAttribute('aria-label', 'Lưu bài viết');
        saveButton.setAttribute('aria-pressed', 'false');
        saveButton.title = 'Lưu bài viết';
        actionRow.append(saveButton);
      }
    });
    document.querySelectorAll('[data-comment-id]:not([data-features])').forEach(comment => {
      comment.dataset.features = 'true';
      if (comment.querySelector('[data-comment-menu]')) return;
      const menu = element('div', null, 'comment-menu');
      menu.dataset.commentMenu = 'true';
      const trigger = element('button', null, 'comment-menu__trigger');
      trigger.type = 'button';
      trigger.dataset.commentMenuTrigger = 'true';
      trigger.setAttribute('aria-label', 'Tùy chọn bình luận');
      trigger.setAttribute('aria-haspopup', 'menu');
      trigger.setAttribute('aria-expanded', 'false');
      trigger.title = 'Tùy chọn bình luận';
      trigger.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="12" cy="5" r="1.7"/><circle cx="12" cy="12" r="1.7"/><circle cx="12" cy="19" r="1.7"/></svg>';
      const list = element('div', null, 'comment-menu__list');
      list.setAttribute('role', 'menu');
      const reportButton = button('Báo cáo bình luận', async () => {
        closeCommentMenus();
        await report('COMMENT', comment.dataset.commentId);
      });
      reportButton.className = 'comment-menu__item';
      reportButton.dataset.commentMenuItem = 'true';
      reportButton.setAttribute('role', 'menuitem');
      list.append(reportButton);
      menu.append(trigger, list);
      comment.append(menu);
    });
    const profile = document.querySelector('[data-profile-user-id]:not([data-features])');
    const viewingOwnProfile = profile && (
      profile.dataset.ownProfile === 'true' ||
      String(profile.dataset.profileUserId || '').trim() === String(document.body.dataset.currentUserId || '').trim()
    );
    if (profile && !viewingOwnProfile) {
      profile.dataset.features = 'true';
      const id = profile.dataset.profileUserId;
      profile.append(button('Chặn', async () => {
        if (!(await AlouteUI.confirm({ title: 'Chặn tài khoản', message: 'Chặn tài khoản này? Hai bạn sẽ không thể xem hồ sơ hoặc liên hệ trực tiếp.', okText: 'Chặn' }))) return;
        await api('/api/blocks/' + id, 'PUT'); location.href = '/settings';
      }), button('Báo cáo tài khoản', () => report('USER', id)));
    }
    if (profile && viewingOwnProfile) profile.dataset.features = 'true';
  }
  let recentGoogleReauthToken = null;
  async function securityCredentials(passwordId, codeId) {
    const values = {
      password: document.getElementById(passwordId)?.value || '',
      code: document.getElementById(codeId)?.value || ''
    };
    if (values.password.trim()) return values;
    try {
      if (recentGoogleReauthToken) values.firebaseIdToken = recentGoogleReauthToken;
      else {
        if (!window.AlouteFirebase || typeof window.AlouteFirebase.getGoogleReauthenticationToken !== 'function') {
          throw new Error('Không thể mở xác minh Google. Hãy nhập mật khẩu hiện tại.');
        }
        recentGoogleReauthToken = await window.AlouteFirebase.getGoogleReauthenticationToken();
        values.firebaseIdToken = recentGoogleReauthToken;
      }
      return values;
    } catch (error) {
      throw new Error(error?.message || 'Hãy nhập mật khẩu hiện tại hoặc xác minh bằng Google.');
    }
  }
  function credentials() { return securityCredentials('securityPassword', 'securityCode'); }
  function accountCredentials() { return securityCredentials('accountPassword', 'accountCode'); }
  async function setupTwoFactor() {
    const setup = await api('/api/account/2fa/setup', 'POST', await credentials());
    dialog('Thêm vào ứng dụng Authenticator', modal => {
      modal.append(element('p', 'Nhập khóa này vào ứng dụng Authenticator rồi nhập mã 6 chữ số.'), element('p', setup.secret, 'feature-secret'));
      const otp = field(modal, 'Mã xác nhận');
      modal.append(button('Bật 2FA', async () => {
        const values = await credentials(); values.code = otp.value;
        const recovery = await api('/api/account/2fa/enable', 'POST', values);
        modal.replaceChildren(element('h2', 'Lưu mã khôi phục'));
        recovery.forEach(value => modal.append(element('p', value, 'feature-secret')));
        modal.append(button('Đã lưu mã', () => location.reload()));
      }));
    });
  }
  async function review(node, decision) {
    await api('/api/admin/moderation', 'POST', {
      targetType: node.dataset.reviewType,
      targetId: node.dataset.reviewId,
      reportId: node.dataset.reportId || null,
      decision
    });
    node.remove();
  }
  function appendFollowRequest(list, request) {
    if (!list || !request || list.querySelector('[data-request-id="' + CSS.escape(String(request.id)) + '"]')) return;
    list.querySelector('.feature-empty')?.remove();
    const row = element('div', null, 'feature-list__row'); row.dataset.requestId = request.id;
    const link = element('a', null, 'feature-request-account'); link.href = Aloute.profileUrl(request.followerUsername, request.followerId);
    const avatar = element('img', null, 'follow-request-avatar'); avatar.src = request.followerAvatarUrl || '/images/avatar_default.png'; avatar.alt = 'Ảnh đại diện'; avatar.loading = 'lazy';
    const identity = element('div', null, 'follow-request-identity');
    identity.append(element('strong', request.followerFullName?.trim() || request.followerUsername || 'Tài khoản không còn khả dụng'));
    if (request.followerUsername) identity.append(element('small', '@' + request.followerUsername));
    link.append(avatar, identity);
    const actions = element('div', null, 'follow-request-actions'); actions.dataset.followRequestActions = request.id;
    const accept = element('button', 'Chấp nhận'); accept.type = 'button'; accept.dataset.action = 'accept-follow'; accept.dataset.requestId = request.id;
    const reject = element('button', 'Từ chối'); reject.type = 'button'; reject.dataset.action = 'reject-follow'; reject.dataset.requestId = request.id;
    accept.dataset.followDecision = 'accept'; reject.dataset.followDecision = 'reject';
    actions.append(accept, reject); row.append(link, actions); list.appendChild(row);
  }

  function refreshRequestEmptyState() {
    const list = document.getElementById('followRequestList');
    if (list && !list.querySelector('.feature-list__row, .feature-empty'))
      list.append(element('div', 'Không có yêu cầu theo dõi đang chờ.', 'feature-empty compact'));
  }
  function appendBlockedAccount(list, block) {
    if (!list || !block || list.querySelector('[data-block-id="' + CSS.escape(String(block.targetId)) + '"]')) return;
    list.querySelector('.feature-empty')?.remove();
    const row = element('div', null, 'feature-list__row'); row.dataset.blockId = block.targetId;
    const account = element('a', null, 'feature-request-account');
    account.href = Aloute.profileUrl(block.targetUsername, block.targetId);
    account.dataset.blockProfile = ''; account.title = 'Bỏ chặn để xem trang cá nhân';
    const avatar = element('img', null, 'follow-request-avatar');
    avatar.src = block.targetAvatarUrl || '/images/avatar_default.png'; avatar.alt = 'Ảnh đại diện'; avatar.loading = 'lazy';
    const identity = element('div', null, 'follow-request-identity');
    identity.append(element('strong', String(block.targetFullName || '').trim() || block.targetUsername || 'Tài khoản không còn khả dụng'));
    if (block.targetUsername) identity.append(element('small', '@' + block.targetUsername));
    account.append(avatar, identity);
    const actions = element('div', null, 'follow-request-actions');
    const unblock = element('button', 'Bỏ chặn'); unblock.type = 'button'; unblock.dataset.action = 'unblock'; unblock.dataset.userId = block.targetId;
    actions.append(unblock); row.append(account, actions); list.appendChild(row);
  }
  function showEmptyBlockedState(host) {
    if (!host) return;
    const list = host.querySelector('#blockedAccountList');
    if (list && !list.querySelector('[data-block-id], .feature-empty') && !host.querySelector('[data-action="load-more-blocks"]'))
      list.append(element('div', 'Bạn chưa chặn tài khoản nào.', 'feature-empty compact'));
  }
  async function loadSocialPage(target, kind) {
    const cursor = target.dataset.cursor || '';
    const page = await api('/api/' + kind + '/cursor?size=50&cursor=' + encodeURIComponent(cursor));
    const list = document.getElementById(kind === 'blocks' ? 'blockedAccountList' : 'followRequestList');
    (page && Array.isArray(page.items) ? page.items : []).forEach(item => {
      if (kind === 'blocks') appendBlockedAccount(list, item); else appendFollowRequest(list, item);
    });
    target.dataset.cursor = page && page.nextCursor ? page.nextCursor : '';
    if (!page || page.hasMore !== true) target.remove();
    if (kind === 'blocks') showEmptyBlockedState(document.getElementById('featureContent'));
  }
  function bindFeaturePage() {
    const host = document.getElementById('featureContent');
    if (!host || host.dataset.actionsBound) return;
    host.dataset.actionsBound = 'true';
    const settingsButtons = [...host.querySelectorAll('[data-settings-target]')];
    const settingsPanels = [...host.querySelectorAll('[data-settings-panel]')];
    const showSettingsPanel = name => {
      const exists = settingsPanels.some(panel => panel.dataset.settingsPanel === name);
      const selected = exists ? name : 'profile';
      settingsButtons.forEach(button => button.classList.toggle('is-active', button.dataset.settingsTarget === selected));
      settingsPanels.forEach(panel => { panel.hidden = panel.dataset.settingsPanel !== selected; });
      if (history.replaceState) history.replaceState(history.state, '', location.pathname + location.search + '#settings-' + selected);
    };
    settingsButtons.forEach(button => button.addEventListener('click', () => showSettingsPanel(button.dataset.settingsTarget)));
    const initialSettings = location.hash.startsWith('#settings-') ? location.hash.slice(10) : 'profile';
    if (settingsPanels.length) showSettingsPanel(initialSettings);
    document.getElementById('settingsSearch')?.addEventListener('input', event => {
      const query = event.target.value.trim().toLocaleLowerCase('vi');
      settingsButtons.forEach(button => { button.hidden = query && !button.textContent.toLocaleLowerCase('vi').includes(query); });
    });
    const settingsBio = document.getElementById('editBio');
    const settingsBioCount = document.getElementById('settingsBioCount');
    if (settingsBio && settingsBioCount) {
      const updateBioCount = () => { settingsBioCount.textContent = settingsBio.value.length; };
      settingsBio.addEventListener('input', updateBioCount);
      updateBioCount();
    }
    const status = document.getElementById('twoFactorStatus');
    if (status) api('/api/account/2fa').then(value => {
      status.textContent = value.enabled ? '2FA đang bật' : '2FA chưa bật';
      status.classList.toggle('is-enabled', value.enabled);
    }).catch(error => { status.textContent = error.message; });
    host.addEventListener('click', async event => {
      if (event.target.closest('[data-block-profile]')) {
        event.preventDefault();
        Aloute.toast('Bạn cần bỏ chặn để xem trang cá nhân này.');
        return;
      }
      const target = event.target.closest('[data-action]'); if (!target || target.hasAttribute('data-follow-decision')) return;
      const action = target.dataset.action; target.disabled = true;
      try {
        if (action === 'unsave') { await api('/api/posts/' + target.dataset.postId + '/saved', 'DELETE'); target.closest('[data-saved-post]').remove(); }
        else if (action === 'accept-follow' || action === 'reject-follow') { await api('/api/follow-requests/' + encodeURIComponent(target.dataset.requestId), action === 'accept-follow' ? 'PUT' : 'DELETE'); target.closest('[data-request-id]').remove(); }
        else if (action === 'unblock') { await api('/api/blocks/' + target.dataset.userId, 'DELETE'); target.closest('[data-block-id]').remove(); showEmptyBlockedState(host); }
        else if (action === 'load-more-requests') await loadSocialPage(target, 'follow-requests');
        else if (action === 'load-more-blocks') await loadSocialPage(target, 'blocks');
        else if (action === 'setup-2fa') await setupTwoFactor();
        else if (action === 'disable-2fa') { await api('/api/account/2fa/disable', 'POST', await credentials()); location.reload(); }
        else if (action === 'deactivate-account' || action === 'delete-account') {
          const deleting = action === 'delete-account';
          if (!(await AlouteUI.confirm({ title: deleting ? 'Xóa tài khoản' : 'Vô hiệu hóa tài khoản', message: deleting ? 'Xóa tài khoản và nội dung? Thao tác này không thể hoàn tác.' : 'Vô hiệu hóa tài khoản?', okText: deleting ? 'Xóa' : 'Vô hiệu hóa' }))) return;
          await api('/api/account/' + (deleting ? 'delete' : 'deactivate'), 'POST', await accountCredentials()); location.href = '/login';
        } else if (action === 'review-content') {
          const item = target.closest('[data-review-type]');
          const content = await api('/api/admin/content/' + item.dataset.reviewType + '/' + encodeURIComponent(item.dataset.reviewId));
          dialog('Nội dung cần duyệt', modal => { modal.append(element('p', content?.content || content?.username || 'Nội dung đã bị xóa')); if (content?.imageUrl) { const image = element('img'); image.src = content.imageUrl; modal.append(image); } });
        } else if (action.endsWith('-review')) {
          const decisions = { 'approve-review': 'APPROVE', 'hide-review': 'HIDE', 'dismiss-review': 'DISMISS' };
          await review(target.closest('[data-review-type]'), decisions[action]);
        }
      } catch (error) { Aloute.toast(error.message, true); } finally { target.disabled = false; }
    });
  }
  function initializePage() {
    enhance(); bindFeaturePage();
    const search = document.getElementById('searchInput');
    const query = new URLSearchParams(location.search).get('q');
    if (search && query) {
      search.value = query;
      if (query.startsWith('#')) document.querySelector('.search-tab[data-tab="posts"]')?.click();
      search.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  function init() {
    initializePage();
    let scheduled = false;
    new MutationObserver(() => {
      if (scheduled) return; scheduled = true;
      requestAnimationFrame(() => { scheduled = false; enhance(); });
    }).observe(document.body, { childList: true, subtree: true });
  }
  document.addEventListener('click', async event => {
    const button = event.target.closest('[data-follow-decision]');
    if (!button) return;
    event.preventDefault();
    const requestId = button.dataset.requestId;
    const groups = [...document.querySelectorAll('[data-follow-request-actions]')]
      .filter(group => group.dataset.followRequestActions === requestId);
    if (!requestId || groups.some(group => group.dataset.busy === 'true')) return;
    groups.forEach(group => {
      group.dataset.busy = 'true';
      group.querySelectorAll('button').forEach(control => { control.disabled = true; });
    });
    const decision = button.dataset.followDecision;
    let expired = false;
    try {
      await api('/api/follow-requests/' + encodeURIComponent(requestId), decision === 'accept' ? 'PUT' : 'DELETE');
    } catch (error) {
      if (error.status === 404) expired = true;
      else {
        groups.forEach(group => {
          delete group.dataset.busy;
          group.querySelectorAll('button').forEach(control => { control.disabled = false; });
        });
        Aloute.toast(error.message, true);
        return;
      }
    }
    const reload = groups.some(group => group.dataset.followProfile === 'true');
    const notifications = new Map();
    groups.forEach(group => {
      const notification = group.closest('[data-notification-id]');
      if (notification) notifications.set(notification.dataset.notificationId, notification);
      const row = group.closest('#followRequestList .feature-list__row');
      if (row) row.remove();
      else group.replaceWith(element('span', expired ? 'Yêu cầu không còn hiệu lực' : decision === 'accept' ? 'Đã chấp nhận' : 'Đã từ chối', 'follow-request-result'));
    });
    await Promise.allSettled([...notifications].map(async ([id, row]) => {
      await api('/api/notifications/' + encodeURIComponent(id) + '/read', 'POST');
      row.classList.remove('notif-item--unread');
    }));
    refreshRequestEmptyState();
    document.dispatchEvent(new CustomEvent('aloute:follow-request-resolved', { detail: { requestId, decision } }));
    if (reload) location.reload();
  });
  const requestHintVersions = new Map();
  document.addEventListener('aloute:follow-request-updated', async event => {
    const payload = event.detail || {};
    if (String(payload.targetId) !== String(currentUser)) return;
    const id = String(payload.followerId) + ':' + String(payload.targetId);
    const generation = (requestHintVersions.get(id) || 0) + 1;
    requestHintVersions.set(id, generation);
    const list = document.getElementById('followRequestList');
    if (payload.eventType === 'REQUEST_CREATED' && list) {
      try {
        const page = await api('/api/follow-requests/cursor?size=50');
        if (requestHintVersions.get(id) !== generation) return;
        const request = page.items?.find(item => item.id === id);
        if (request) {
          appendFollowRequest(list, request);
          const row = [...list.querySelectorAll('.feature-list__row')].find(node => node.dataset.requestId === id);
          if (row) list.prepend(row);
        }
      } catch (_) { /* Persisted request list remains available on reload/poll. */ }
    } else if (payload.eventType === 'REQUEST_CANCELLED' || payload.eventType === 'REQUEST_DECIDED') {
      document.querySelectorAll('[data-follow-request-actions]').forEach(group => {
        if (group.dataset.followRequestActions !== id) return;
        const row = group.closest('#followRequestList .feature-list__row');
        if (row) row.remove();
        else group.replaceWith(element('span', 'Yêu cầu đã được xử lý', 'follow-request-result'));
      });
      refreshRequestEmptyState();
    }
  });
  document.addEventListener('aloute:navigated', initializePage);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();

