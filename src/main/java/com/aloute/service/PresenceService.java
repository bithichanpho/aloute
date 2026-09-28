
package com.aloute.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;

@Service
public class PresenceService {

    private final Map<String, Integer> onlineConnectionsByUsername = new ConcurrentHashMap<>();

    private final IUserRepository userRepository;
    private final FriendService friendService;
    private final SimpMessagingTemplate messagingTemplate;

    public PresenceService(
            IUserRepository userRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate) {
        this.userRepository = userRepository;
        this.friendService = friendService;
        this.messagingTemplate = messagingTemplate;
    }

    public void userConnected(String username) {
        int count = onlineConnectionsByUsername.merge(username, 1, Integer::sum);
        if (count == 1) {
            broadcastPresence(username, true);
        }
    }

    public void userDisconnected(String username) {
        Integer remaining =
                onlineConnectionsByUsername.computeIfPresent(
                        username, (k, v) -> v <= 1 ? null : v - 1);
        if (remaining == null) {
            broadcastPresence(username, false);
        }
    }

    public boolean isOnlineUsername(String username) {
        return username != null && onlineConnectionsByUsername.containsKey(username);
    }

    public boolean isOnline(Long userId) {
        if (userId == null) return false;
        return userRepository
                .findById(userId)
                .map(User::getUsername)
                .map(this::isOnlineUsername)
                .orElse(false);
    }

    private void broadcastPresence(String username, boolean online) {
        userRepository
                .findByUsername(username)
                .ifPresent(
                        user -> {
                            Map<String, Object> payload =
                                    Map.of("userId", user.getId(), "online", online);
                            for (Long friendId : friendService.getMutualFriendIds(user.getId())) {
                                userRepository
                                        .findById(friendId)
                                        .ifPresent(
                                                friend ->
                                                        messagingTemplate.convertAndSendToUser(
                                                                friend.getUsername(),
                                                                "/queue/presence",
                                                                payload));
                            }
                        });
    }
}
