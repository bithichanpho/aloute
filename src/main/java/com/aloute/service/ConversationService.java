package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.UserBlock;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.User;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Read/query orchestration for the direct and group conversation inbox. */
@Service
public class ConversationService {
    private final IUserRepository userRepository;
    private final IGroupReadStatusRepository groupReadStatusRepository;
    private final GroupService groupService;
    private final FriendService friendService;
    private final PresenceService presenceService;
    private final MongoTemplate mongoTemplate;
    private final ContentAccessService access;

    public ConversationService(
            IUserRepository userRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            GroupService groupService,
            FriendService friendService,
            PresenceService presenceService,
            MongoTemplate mongoTemplate,
            ContentAccessService access) {
        this.userRepository = userRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.groupService = groupService;
        this.friendService = friendService;
        this.presenceService = presenceService;
        this.mongoTemplate = mongoTemplate;
        this.access = access;
    }

    public long countUnreadGroups(List<ChatGroup> groups, Long userId) {
        return groupUnreadCounts(groups, userId).values().stream().mapToLong(Long::longValue).sum();
    }

    private Set<String> excludedUnreadSenderIds(Long userId) {
        Set<Long> excluded = new java.util.HashSet<>();
        mongoTemplate.find(
                        org.springframework.data.mongodb.core.query.Query.query(new Criteria().orOperator(
                                Criteria.where("ownerId").is(userId),
                                Criteria.where("targetId").is(userId))),
                        UserBlock.class)
                .forEach(block -> {
                    Long otherId = userId.equals(block.getOwnerId()) ? block.getTargetId() : block.getOwnerId();
                    if (otherId != null && !userId.equals(otherId)) excluded.add(otherId);
                });
        userRepository.findByActiveFalse().stream()
                .map(User::getId)
                .filter(java.util.Objects::nonNull)
                .forEach(excluded::add);
        return excluded.stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private Map<String, Long> groupUnreadCounts(List<ChatGroup> groups, Long userId) {
        if (groups.isEmpty()) {
            return Map.of();
        }

        Map<Long, LocalDateTime> readTimes =
                groupReadStatusRepository.findByUserId(userId).stream()
                        .collect(
                                Collectors.toMap(
                                        GroupReadStatus::getGroupId,
                                        GroupReadStatus::getLastReadAt));

        Set<String> excludedSenderIds = excludedUnreadSenderIds(userId);
        Criteria[] filters =
                groups.stream()
                        .map(
                                g -> {
                                    Criteria senderFilter = Criteria.where("senderId")
                                            .ne(String.valueOf(userId));
                                    if (!excludedSenderIds.isEmpty()) senderFilter.nin(excludedSenderIds);

                                    java.util.List<Criteria> clauses = new java.util.ArrayList<>();
                                    clauses.add(Criteria.where("conversationId")
                                            .is(ChatMessage.buildGroupConversationId(g.getId())));
                                    clauses.add(senderFilter);
                                    clauses.add(Criteria.where("hiddenForUserIds").nin(String.valueOf(userId)));
                                    if (readTimes.containsKey(g.getId())) {
                                        clauses.add(Criteria.where("sentAt").gt(readTimes.get(g.getId())));
                                    }
                                    return new Criteria().andOperator(clauses.toArray(Criteria[]::new));
                                })
                        .toArray(Criteria[]::new);

        Map<String, Long> counts = new java.util.HashMap<>();
        mongoTemplate
                .aggregate(
                        Aggregation.newAggregation(
                                Aggregation.match(new Criteria().orOperator(filters)),
                                Aggregation.group("conversationId").count().as("count")),
                        ChatMessage.class,
                        Document.class)
                .forEach(
                        d -> counts.put(d.getString("_id"), ((Number) d.get("count")).longValue()));
        return counts;
    }

    public List<ConversationResponse> getConversations(Long userId, Long ensureOtherUserId) {
        List<ConversationResponse> conversations = getConversations(userId);
        if (ensureOtherUserId == null || ensureOtherUserId.equals(userId)) {
            return conversations;
        }
        access.requireProfile(userId, ensureOtherUserId);
        boolean already =
                conversations.stream()
                        .anyMatch(
                                c -> !c.isGroup() && ensureOtherUserId.equals(c.getOtherUserId()));
        if (!already && userRepository.existsById(ensureOtherUserId)) {
            conversations.add(0, toFriendPreviewResponse(ensureOtherUserId));
            fillOtherUserInfo(conversations);
        }
        return conversations;
    }

    public List<ConversationResponse> getConversations(Long userId) {
        String userIdStr = String.valueOf(userId);

        List<ChatMessage> latestDirect =
                mongoTemplate
                        .aggregate(
                                Aggregation.newAggregation(
                                        Aggregation.match(
                                                new Criteria()
                                                        .andOperator(
                                                                Criteria.where("groupId").is(null),
                                                                new Criteria()
                                                                        .orOperator(
                                                                                Criteria.where(
                                                                                                "senderId")
                                                                                        .is(
                                                                                                userIdStr),
                                                                                Criteria.where(
                                                                                                "receiverId")
                                                                                        .is(
                                                                                                userIdStr)))),
                                        Aggregation.sort(Sort.Direction.DESC, "sentAt", "_id"),
                                        Aggregation.group("conversationId")
                                                .first(Aggregation.ROOT)
                                                .as("latest"),
                                        Aggregation.replaceRoot("latest"),
                                        Aggregation.sort(Sort.Direction.DESC, "sentAt", "_id"),
                                        Aggregation.limit(100)),
                                ChatMessage.class,
                                ChatMessage.class)
                        .getMappedResults();

        Map<String, Long> unreadBySender = new java.util.HashMap<>();
        Set<String> excludedUnreadSenderIds = excludedUnreadSenderIds(userId);
        Criteria directUnread = new Criteria().andOperator(
                Criteria.where("receiverId").is(userIdStr),
                Criteria.where("groupId").is(null),
                Criteria.where("hiddenForUserIds").nin(userIdStr),
                new Criteria().orOperator(
                        Criteria.where("read").is(false), Criteria.where("read").exists(false)));
        if (!excludedUnreadSenderIds.isEmpty()) directUnread.and("senderId").nin(excludedUnreadSenderIds);
        mongoTemplate
                .aggregate(
                        Aggregation.newAggregation(
                                Aggregation.match(directUnread),
                                Aggregation.group("senderId").count().as("count")),
                        ChatMessage.class,
                        Document.class)
                .forEach(
                        d ->
                                unreadBySender.put(
                                        d.getString("_id"), ((Number) d.get("count")).longValue()));

        List<ConversationResponse> result =
                latestDirect.stream()
                        .map(
                                latest ->
                                        toDirectConversationResponse(
                                                userIdStr, latest, unreadBySender))
                        .collect(Collectors.toList());

        Set<Long> otherIdsWithConversation =
                result.stream()
                        .map(ConversationResponse::getOtherUserId)
                        .collect(Collectors.toSet());

        for (Long friendId : friendService.getMutualFriendIds(userId)) {
            if (!otherIdsWithConversation.contains(friendId)) {
                result.add(toFriendPreviewResponse(friendId));
            }
        }

        List<ChatGroup> groups = groupService.getMyGroups(userId);
        Map<String, Long> groupUnread = groupUnreadCounts(groups, userId);
        Map<Long, Integer> memberCounts =
                groupService.getMemberCounts(
                        groups.stream().map(ChatGroup::getId).collect(Collectors.toList()));

        Map<String, ChatMessage> latestGroupMessages = new java.util.HashMap<>();
        if (!groups.isEmpty()) {
            mongoTemplate
                    .aggregate(
                            Aggregation.newAggregation(
                                    Aggregation.match(
                                            Criteria.where("conversationId")
                                                    .in(
                                                            groups.stream()
                                                                    .map(
                                                                            g ->
                                                                                    ChatMessage
                                                                                            .buildGroupConversationId(
                                                                                                    g
                                                                                                            .getId()))
                                                                    .collect(Collectors.toList()))),
                                    Aggregation.sort(Sort.Direction.DESC, "sentAt", "_id"),
                                    Aggregation.group("conversationId")
                                            .first(Aggregation.ROOT)
                                            .as("latest"),
                                    Aggregation.replaceRoot("latest")),
                            ChatMessage.class,
                            ChatMessage.class)
                    .forEach(m -> latestGroupMessages.put(m.getConversationId(), m));
        }

        for (ChatGroup group : groups) {
            String conversationId = ChatMessage.buildGroupConversationId(group.getId());
            result.add(
                    toGroupConversationResponse(
                            group,
                            latestGroupMessages.get(conversationId),
                            memberCounts.getOrDefault(group.getId(), 0),
                            groupUnread.getOrDefault(conversationId, 0L)));
        }

        Set<Long> visibleProfiles =
                access.visibleProfileIds(
                        userId,
                        result.stream()
                                .filter(c -> !c.isGroup())
                                .map(ConversationResponse::getOtherUserId)
                                .toList());
        result.removeIf(c -> !c.isGroup() && !visibleProfiles.contains(c.getOtherUserId()));
        fillOtherUserInfo(result);

        result.sort(
                (a, b) -> {
                    LocalDateTime ta = a.getLastMessageAt();
                    LocalDateTime tb = b.getLastMessageAt();
                    if (ta == null && tb == null) {
                        return nameOf(a).compareToIgnoreCase(nameOf(b));
                    }
                    if (ta == null) return 1;
                    if (tb == null) return -1;
                    return tb.compareTo(ta);
                });

        return result;
    }

    private String nameOf(ConversationResponse c) {
        String name = c.isGroup() ? c.getGroupName() : c.getOtherFullName();
        return name != null ? name : "";
    }

    private ConversationResponse toDirectConversationResponse(
            String currentUserIdStr, ChatMessage latest, Map<String, Long> unreadBySender) {
        boolean iAmSender = latest.getSenderId().equals(currentUserIdStr);
        String otherIdStr = iAmSender ? latest.getReceiverId() : latest.getSenderId();
        Long otherId = Long.valueOf(otherIdStr);

        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(false);
        dto.setOtherUserId(otherId);
        dto.setLastMessage(previewText(latest));
        dto.setLastMessageAt(latest.getSentAt());
        dto.setLastMessageTimeAgo(TimeAgoUtil.format(latest.getSentAt()));
        dto.setUnreadCount(unreadBySender.getOrDefault(otherIdStr, 0L));

        return dto;
    }

    private ConversationResponse toFriendPreviewResponse(Long friendId) {
        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(false);
        dto.setOtherUserId(friendId);
        dto.setHasConversation(false);
        dto.setUnreadCount(0);

        return dto;
    }

    private void fillOtherUserInfo(List<ConversationResponse> conversations) {
        Set<Long> ids =
                conversations.stream()
                        .filter(
                                c ->
                                        !c.isGroup()
                                                && c.getOtherUserId() != null
                                                && c.getOtherUsername() == null)
                        .map(ConversationResponse::getOtherUserId)
                        .collect(Collectors.toSet());
        if (ids.isEmpty()) return;

        Map<Long, User> byId =
                userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        for (ConversationResponse c : conversations) {
            if (c.isGroup() || c.getOtherUserId() == null) continue;
            User u = byId.get(c.getOtherUserId());
            if (u == null) continue;
            c.setOtherUsername(u.getUsername());
            c.setOtherFullName(u.getFullName());
            c.setOtherAvatarUrl(AvatarUtil.resolve(u.getAvatarUrl()));
            c.setOnline(presenceService.isOnlineUsername(u.getUsername()));
        }
    }

    private ConversationResponse toGroupConversationResponse(
            ChatGroup group, ChatMessage latest, int memberCount, long unreadCount) {

        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(true);
        dto.setGroupId(group.getId());
        dto.setGroupName(group.getName());
        dto.setOtherAvatarUrl(group.getAvatarUrl());
        dto.setMemberCount(memberCount);
        dto.setUnreadCount(unreadCount);

        if (latest != null) {
            dto.setLastMessage(previewText(latest));
            dto.setLastMessageAt(latest.getSentAt());
            dto.setLastMessageTimeAgo(TimeAgoUtil.format(latest.getSentAt()));
        } else {
            dto.setLastMessage("Nhóm vừa được tạo");
            dto.setLastMessageAt(group.getCreatedAt());
            dto.setLastMessageTimeAgo(TimeAgoUtil.format(group.getCreatedAt()));
        }

        return dto;
    }

    private String previewText(ChatMessage message) {
        if ("CALL".equalsIgnoreCase(message.getMessageType())) {
            String call =
                    "audio".equalsIgnoreCase(message.getCallType())
                            ? "Cuộc gọi thoại"
                            : "Cuộc gọi video";
            if ("MISSED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " nhỡ";
            if ("REJECTED".equalsIgnoreCase(message.getCallStatus()))
                return "☎ " + call + " bị từ chối";
            return "☎ " + call;
        }
        if (message.getMediaType() != null) {
            if ("video".equalsIgnoreCase(message.getMediaType())) return "🎥 Video";
            if ("file".equalsIgnoreCase(message.getMediaType()))
                return "📎 "
                        + (message.getMediaName() != null
                                ? message.getMediaName()
                                : "Tệp đính kèm");
            return "📷 Ảnh";
        }
        return message.getContent() != null ? message.getContent() : "";
    }

}
