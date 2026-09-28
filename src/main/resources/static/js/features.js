(() => {
  'use strict';
  const api = (url, method = 'GET', body) => Aloute.api(url, { method, body });
  const currentUser = document.body.dataset.currentUserId;
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
      link.href = match[3] === '@' ? '/people/' + encodeURIComponent(match[4]) : '/search?q=' + encodeURIComponent(match[0]); fragment.append(link);
      end = match.index + match[0].length;
    }
    fragment.append(document.createTextNode(text.slice(end))); node.replaceChildren(fragment);
  }
  function editPost(id) {
    return api('/api/posts/' + id).then(post => dialog('Chỉnh sửa bài viết', modal => {
      const content = field(modal, 'Nội dung', 'textarea', post.content); content.maxLength = 2000;
      const label = element('label', 'Ai có thể xem?');
      const select = element('select');
      [['PUBLIC', 'Công khai'], ['FRIENDS', 'Bạn bè'], ['ONLY_ME', 'Chỉ mình tôi']].forEach(([value, text]) => {
        const option = element('option', text); option.value = value; select.append(option);
      });
      select.value = post.visibility || 'PUBLIC'; label.append(select); modal.append(label);
      modal.append(button('Lưu thay đổi', async () => {
        await api('/api/posts/' + id, 'PUT', { content: content.value, imageUrl: post.imageUrl, videoUrl: post.videoUrl, visibility: select.value });
        location.reload();
      }));
    }));
  }
  function enhance() {
    document.querySelectorAll('.post__content').forEach(linkTags);
    if (!currentUser) return;
    document.querySelectorAll('.post[data-post-id]:not([data-features])').forEach(post => {
      post.dataset.features = 'true';
      const id = post.dataset.actionPostId || post.dataset.postId;
      const actions = element('div', null, 'feature-actions');
      let saved = false;
      actions.append(button('Lưu bài', async node => {
        await api('/api/posts/' + id + '/saved', saved ? 'DELETE' : 'PUT'); saved = !saved; node.textContent = saved ? 'Bỏ lưu' : 'Lưu bài';
      }), button('Báo cáo', () => report('POST', id)));
      if (post.querySelector('.post__delete')) actions.append(button('Sửa', () => editPost(id)), button('Lịch sử sửa', async () => {
        const history = await api('/api/posts/' + id + '/history');
        dialog('Lịch sử chỉnh sửa', modal => {
          if (!history.length) modal.append(element('p', 'Chưa có lần chỉnh sửa nào.'));
          history.forEach(item => { modal.append(element('h4', new Date(item.createdAt).toLocaleString()), element('p', item.content)); });
        });
      }));
      (post.querySelector('.post__body') || post).append(actions);
    });
    document.querySelectorAll('[data-comment-id]:not([data-features])').forEach(comment => {
      comment.dataset.features = 'true'; comment.append(button('Báo cáo', () => report('COMMENT', comment.dataset.commentId)));
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
        if (!confirm('Chặn tài khoản này? Hai bạn sẽ không thể xem hồ sơ hoặc liên hệ trực tiếp.')) return;
        await api('/api/blocks/' + id, 'PUT'); location.href = '/settings';
      }), button('Báo cáo tài khoản', () => report('USER', id)));
    }
    if (profile && viewingOwnProfile) profile.dataset.features = 'true';
  }
  function credentials() {
    return {
      password: document.getElementById('securityPassword')?.value || '',
      code: document.getElementById('securityCode')?.value || ''
    };
  }
  function openStory(trigger) {
    const story = trigger.dataset;
    const owned = story.storyOwned === 'true';
    api('/api/stories/' + encodeURIComponent(story.storyId) + '/view', 'POST').then(result => {
      story.storyViews = String(result.views ?? story.storyViews ?? 0);
      trigger.classList.add('is-viewed');
    }).catch(() => {});
    const modal = element('dialog', null, 'story-viewer');
    const shell = element('div', null, 'story-viewer__shell');
    const header = element('header', null, 'story-viewer__header');
    const avatar = element('img');
    avatar.src = story.storyAvatar || '/images/avatar_default.png';
    const identity = element('div');
    identity.append(element('strong', story.storyName || story.storyUsername || 'Người dùng'));
    identity.append(element('span', '@' + (story.storyUsername || '')));
    header.append(avatar, identity);
    const close = button('×', () => modal.close()); close.className = 'story-viewer__close'; header.append(close);
    const stage = element('div', null, 'story-viewer__stage');
    if (story.storyVideo) {
      const video = element('video'); video.src = story.storyVideo; video.controls = true; video.autoplay = true; stage.append(video);
    } else if (story.storyImage) {
      const image = element('img'); image.src = story.storyImage; image.alt = 'Tin của ' + (story.storyUsername || 'người dùng'); stage.append(image);
    }
    if (story.storyContent) stage.append(element('p', story.storyContent, 'story-viewer__caption'));
    const footer = element('footer', null, 'story-viewer__footer');
    if (owned) {
      const viewers = button((story.storyViews || '0') + ' người đã xem', async () => {
        const list = await api('/api/stories/' + encodeURIComponent(story.storyId) + '/viewers');
        dialog('Người đã xem tin', content => {
          const rows = element('div', null, 'story-viewers');
          if (!list.length) rows.append(element('p', 'Chưa có ai xem tin này.'));
          list.forEach(viewer => {
            const row = element('a', null, 'story-viewers__row'); row.href = '/profile/' + viewer.userId;
            const photo = element('img'); photo.src = viewer.avatarUrl || '/images/avatar_default.png';
            row.append(photo, element('span', viewer.fullName || viewer.username)); rows.append(row);
          });
          content.append(rows);
        });
      });
      footer.append(viewers);
    } else {
      const reply = element('form', null, 'story-viewer__reply');
      const input = element('input'); input.placeholder = 'Trả lời tin…'; input.maxLength = 1000; input.required = true;
      const send = element('button', 'Gửi'); send.type = 'submit';
      const sentState = element('span', '', 'story-viewer__sent');
      reply.append(input, send, sentState);
      reply.addEventListener('submit', async event => {
        event.preventDefault(); send.disabled = true;
        try {
          await api('/api/stories/' + encodeURIComponent(story.storyId) + '/replies', 'POST', { content: input.value.trim() });
          input.value = ''; sentState.textContent = 'Đã gửi'; Aloute.toast('Đã gửi phản hồi vào tin nhắn');
        } catch (error) { Aloute.toast(error.message, true); } finally { send.disabled = false; }
      });
      const heart = button(story.storyLiked === 'true' ? '♥' : '♡', async node => {
        const liked = story.storyLiked !== 'true';
        await api('/api/stories/' + encodeURIComponent(story.storyId) + '/reaction', liked ? 'PUT' : 'DELETE');
        story.storyLiked = String(liked); node.textContent = liked ? '♥' : '♡'; node.classList.toggle('is-liked', liked);
        if (liked) Aloute.toast('Đã thả tim tin');
      });
      heart.className = 'story-viewer__heart'; heart.classList.toggle('is-liked', story.storyLiked === 'true');
      footer.append(reply, heart);
    }
    shell.append(header, stage, footer); modal.append(shell);
    modal.addEventListener('click', event => { if (event.target === modal) modal.close(); });
    modal.addEventListener('close', () => modal.remove()); document.body.append(modal); modal.showModal();
  }

  function bindStoryViewer() {
    if (document.body.dataset.storyViewerBound) return;
    document.body.dataset.storyViewerBound = 'true';
    document.body.addEventListener('click', event => {
      if (event.target.closest('[data-action]')) return;
      const trigger = event.target.closest('[data-story-open]');
      if (trigger) { event.preventDefault(); openStory(trigger); }
    });
  }
  function accountCredentials() {
    return {
      password: document.getElementById('accountPassword')?.value || '',
      code: document.getElementById('accountCode')?.value || ''
    };
  }
  async function setupTwoFactor() {
    const setup = await api('/api/account/2fa/setup', 'POST', credentials());
    dialog('Thêm vào ứng dụng Authenticator', modal => {
      modal.append(element('p', 'Nhập khóa này vào ứng dụng Authenticator rồi nhập mã 6 chữ số.'), element('p', setup.secret, 'feature-secret'));
      const otp = field(modal, 'Mã xác nhận');
      modal.append(button('Bật 2FA', async () => {
        const values = credentials(); values.code = otp.value;
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
    const media = document.getElementById('storyMedia');
    if (media) media.addEventListener('change', () => {
      const preview = document.getElementById('storyPreview'); preview.replaceChildren();
      const file = media.files[0]; if (!file) return;
      const video = file.type.startsWith('video/');
      if (!video && !file.type.startsWith('image/')) { media.value = ''; Aloute.toast('Chỉ chấp nhận ảnh hoặc video', true); return; }
      const node = element(video ? 'video' : 'img'); node.src = URL.createObjectURL(file);
      if (video) node.controls = true; preview.append(node);
    });
    document.getElementById('storyComposer')?.addEventListener('submit', async event => {
      event.preventDefault();
      const submit = event.currentTarget.querySelector('[type="submit"]'); submit.disabled = true;
      try {
        const content = document.getElementById('storyContent').value.trim();
        const file = media?.files[0]; let imageUrl = null; let videoUrl = null;
        if (!content && !file) throw new Error('Tin cần có nội dung, hình ảnh hoặc video');
        if (file) {
          const form = new FormData(); form.append('file', file);
          const uploaded = await api('/api/upload/media', 'POST', form);
          if (file.type.startsWith('video/')) videoUrl = uploaded.url; else imageUrl = uploaded.url;
        }
        await api('/api/stories', 'POST', { content, imageUrl, videoUrl }); location.reload();
      } catch (error) { Aloute.toast(error.message, true); } finally { submit.disabled = false; }
    });
    host.addEventListener('click', async event => {
      const target = event.target.closest('[data-action]'); if (!target) return;
      const action = target.dataset.action; target.disabled = true;
      try {
        if (action === 'unsave') { await api('/api/posts/' + target.dataset.postId + '/saved', 'DELETE'); target.closest('[data-saved-post]').remove(); }
        else if (action === 'delete-story') { await api('/api/stories/' + target.dataset.storyId, 'DELETE'); target.closest('[data-story-id]').remove(); }
        else if (action === 'accept-follow' || action === 'reject-follow') { await api('/api/follow-requests/' + encodeURIComponent(target.dataset.requestId), action === 'accept-follow' ? 'PUT' : 'DELETE'); target.closest('[data-request-id]').remove(); }
        else if (action === 'unblock') { await api('/api/blocks/' + target.dataset.userId, 'DELETE'); target.closest('[data-block-id]').remove(); }
        else if (action === 'setup-2fa') await setupTwoFactor();
        else if (action === 'disable-2fa') { await api('/api/account/2fa/disable', 'POST', credentials()); location.reload(); }
        else if (action === 'deactivate-account' || action === 'delete-account') {
          const deleting = action === 'delete-account';
          if (!confirm(deleting ? 'Xóa tài khoản và nội dung? Thao tác này không thể hoàn tác.' : 'Vô hiệu hóa tài khoản?')) return;
          await api('/api/account/' + (deleting ? 'delete' : 'deactivate'), 'POST', accountCredentials()); location.href = '/login';
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
    enhance(); bindFeaturePage(); bindStoryViewer();
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
  document.addEventListener('aloute:navigated', initializePage);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();

