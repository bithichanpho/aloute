package com.aloute.service;

import com.aloute.notifications.application.NotificationApplicationService;
import com.aloute.notifications.api.NotificationIntents;
import com.aloute.notifications.api.ReliableNotificationPush;
import com.aloute.posts.api.PostNotificationLookup;
import com.aloute.posts.infrastructure.NotificationPostReader;
import com.aloute.social.api.ProfileVisibility;
import com.aloute.social.infrastructure.LegacyProfileVisibility;
import com.aloute.users.api.UserDirectory;
import com.aloute.model.entity.User;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** Compatibility facade. The inherited application use cases depend only on public module APIs. */
@Service
public class NotificationService extends NotificationApplicationService {
    @Autowired
    public NotificationService(
            INotificationRepository notifications, UserDirectory users, PostNotificationLookup posts,
            SimpMessagingTemplate messaging, ProfileVisibility access,
            ReliableNotificationPush push, NotificationIntents outbox) {
        super(notifications, users, posts, messaging, access, push, outbox);
    }

    /** Preserves direct Java callers; Spring always uses the public-port constructor above. */
    public NotificationService(
            INotificationRepository notifications, IUserRepository users, IPostRepository posts,
            SimpMessagingTemplate messaging, ContentAccessService access,
            PushNotificationService push, NotificationOutboxService outbox) {
        this(notifications, new UserDirectory() {
            public boolean exists(Long id) { return id != null && users.existsById(id); }
            public Optional<String> username(Long id) {
                return users.findById(id).map(User::getUsername);
            }
            public Map<Long, ProfileLabel> profileLabels(Collection<Long> ids) {
                return new com.aloute.users.infrastructure.JpaUserDirectory(users).profileLabels(ids);
            }
            public Map<Long, String> usernames(Collection<Long> ids) {
                return users.findAllById(ids).stream()
                        .collect(Collectors.toUnmodifiableMap(User::getId, User::getUsername));
            }
        }, new NotificationPostReader(posts, access), messaging,
                new LegacyProfileVisibility(access), push, outbox);
    }
}
