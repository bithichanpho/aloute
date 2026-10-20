package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.ConversationCursorPage;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.UserBlock;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.User;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Read/query orchestration for the direct and group conversation inbox. */
@Service
public class ConversationService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int LEGACY_PAGE_SIZE = 100;
    private static final int GROUP_UNREAD_BATCH_SIZE = 100;

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
        if (groups == null || groups.isEmpty()) return 0L;
        return countUnreadGroupsByIds(
                groups.stream().map(ChatGroup::getId).filter(Objects::nonNull).toList(), userId);
    }

    public long countUnreadGroupsByIds(Collection<Long> groupIds, Long userId) {
        if (groupIds == null || groupIds.isEmpty()) return 0L;
        return groupUnreadCountsByIds(groupIds, userId).values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    public long countUnreadDirect(Long userId) {
        return directUnreadCounts(userId, null).values().stream()
                .mapToLong(Long::longValue).sum();
    }

    /**
     * Keyset-paginates conversations that already contain a message. Once that sequence is
     * exhausted, an opaque offset cursor traverses groups/friends that have no message yet. The
     * expensive empty-conversation scan therefore only runs at the tail of the inbox.
     */
    public ConversationCursorPage getConversationPage(
            Long userId, String encodedCursor, int requestedSize) {
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));
        InboxCursor cursor = decodeCursor(encodedCursor);
        List<Long> groupIds = groupService.getAllMyGroupIds(userId);
        if (cursor != null && cursor.emptyPhase()) {
            return emptyConversationPage(userId, groupIds, cursor.offset(), size);
        }

        List<ChatMessage> latest = loadLatestConversationMessages(
                userId, groupIds, cursor, size + 1);
        if (latest.size() > size) {
            latest = new ArrayList<>(latest.subList(0, size));
            ChatMessage last = latest.get(latest.size() - 1);
            return new ConversationCursorPage(
                    mapLatestMessages(userId, latest), encodeActiveCursor(last), true);
        }

        List<ConversationResponse> items = new ArrayList<>(mapLatestMessages(userId, latest));
        int remaining = size - items.size();
        if (remaining <= 0) {
            ChatMessage last = latest.get(latest.size() - 1);
            return new ConversationCursorPage(items, encodeActiveCursor(last), true);
        }
        List<ConversationResponse> empty = emptyConversations(userId, groupIds);
        int take = Math.min(remaining, empty.size());
        items.addAll(empty.subList(0, take));
        boolean hasMore = take < empty.size();
        return new ConversationCursorPage(items, hasMore ? encodeEmptyCursor(take) : "", hasMore);
    }

    public List<ConversationResponse> getConversations(Long userId, Long ensureOtherUserId) {
        List<ConversationResponse> conversations = getConversations(userId);
        if (ensureOtherUserId == null || ensureOtherUserId.equals(userId)) return conversations;
        access.requireProfile(userId, ensureOtherUserId);
        boolean already = conversations.stream()
                .anyMatch(c -> !c.isGroup() && ensureOtherUserId.equals(c.getOtherUserId()));
        if (!already && userRepository.existsById(ensureOtherUserId)) {
            conversations.add(0, toFriendPreviewResponse(ensureOtherUserId));
            fillOtherUserInfo(conversations);
        }
        return conversations;
    }

    /** Compatibility adapter for Thymeleaf and older clients. New clients use the cursor API. */
    public List<ConversationResponse> getConversations(Long userId) {
        return getConversationPageInternal(userId, LEGACY_PAGE_SIZE).items();
    }

    private ConversationCursorPage getConversationPageInternal(Long userId, int size) {
        List<Long> groupIds = groupService.getAllMyGroupIds(userId);
        List<ChatMessage> latest = loadLatestConversationMessages(userId, groupIds, null, size + 1);
        boolean hasMore = latest.size() > size;
        if (hasMore) latest = new ArrayList<>(latest.subList(0, size));
        List<ConversationResponse> items = new ArrayList<>(mapLatestMessages(userId, latest));
        if (!hasMore && items.size() < size) {
            List<ConversationResponse> empty = emptyConversations(userId, groupIds);
            items.addAll(empty.subList(0, Math.min(size - items.size(), empty.size())));
        }
        String next = hasMore && !latest.isEmpty()
                ? encodeActiveCursor(latest.get(latest.size() - 1)) : "";
        return new ConversationCursorPage(items, next, hasMore);
    }

    private List<ChatMessage> loadLatestConversationMessages(
            Long userId, List<Long> groupIds, InboxCursor cursor, int limit) {
        String userIdString = String.valueOf(userId);
        Criteria direct = new Criteria().andOperator(
                Criteria.where("groupId").is(null),
                new Criteria().orOperator(
                        Criteria.where("senderId").is(userIdString),
                        Criteria.where("receiverId").is(userIdString)));
        Criteria scope = groupIds.isEmpty()
                ? direct
                : new Criteria().orOperator(direct, Criteria.where("groupId").in(groupIds));
        Criteria visible = new Criteria().andOperator(
                scope, Criteria.where("hiddenForUserIds").nin(userIdString));

        List<AggregationOperation> operations = new ArrayList<>();
        operations.add(Aggregation.match(visible));
        operations.add(Aggregation.sort(Sort.by(
                Sort.Order.desc("sentAt"), Sort.Order.desc("_id"))));
        operations.add(Aggregation.group("conversationId")
                .first(Aggregation.ROOT).as("latest"));
        operations.add(Aggregation.replaceRoot("latest"));
        if (cursor != null && !cursor.emptyPhase()) {
            operations.add(Aggregation.match(new Criteria().orOperator(
                    Criteria.where("sentAt").lt(cursor.sentAt()),
                    new Criteria().andOperator(
                            Criteria.where("sentAt").is(cursor.sentAt()),
                            Criteria.where("_id").lt(cursor.id())))));
        }
        operations.add(Aggregation.sort(Sort.by(
                Sort.Order.desc("sentAt"), Sort.Order.desc("_id"))));
        operations.add(Aggregation.limit(limit));
        return mongoTemplate.aggregate(
                        Aggregation.newAggregation(operations), ChatMessage.class, ChatMessage.class)
                .getMappedResults();
    }

    private List<ConversationResponse> mapLatestMessages(
            Long userId, List<ChatMessage> latestMessages) {
        if (latestMessages.isEmpty()) return List.of();
        String userIdString = String.valueOf(userId);
        Set<Long> groupIds = latestMessages.stream().map(ChatMessage::getGroupId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ChatGroup> groups = groupService.getGroupsByIds(groupIds).stream()
                .collect(Collectors.toMap(ChatGroup::getId, group -> group));
        Map<Long, Integer> memberCounts = groupService.getMemberCounts(groupIds);
        Map<String, Long> groupUnread = groupUnreadCountsByIds(groups.keySet(), userId);

        Set<String> directPeers = latestMessages.stream()
                .filter(message -> message.getGroupId() == null)
                .map(message -> userIdString.equals(message.getSenderId())
                        ? message.getReceiverId() : message.getSenderId())
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Long> directUnread = directUnreadCounts(userId, directPeers);

        List<ConversationResponse> result = new ArrayList<>();
        for (ChatMessage message : latestMessages) {
            if (message.getGroupId() != null) {
                ChatGroup group = groups.get(message.getGroupId());
                if (group != null) {
                    result.add(toGroupConversationResponse(
                            group, message, memberCounts.getOrDefault(group.getId(), 0),
                            groupUnread.getOrDefault(message.getConversationId(), 0L)));
                }
            } else {
                result.add(toDirectConversationResponse(userIdString, message, directUnread));
            }
        }
        retainVisibleDirectProfiles(userId, result);
        fillOtherUserInfo(result);
        return result;
    }

    private ConversationCursorPage emptyConversationPage(
            Long userId, List<Long> groupIds, int offset, int size) {
        List<ConversationResponse> empty = emptyConversations(userId, groupIds);
        int start = Math.min(Math.max(0, offset), empty.size());
        int end = Math.min(start + size, empty.size());
        List<ConversationResponse> items = empty.subList(start, end);
        boolean hasMore = end < empty.size();
        return new ConversationCursorPage(items, hasMore ? encodeEmptyCursor(end) : "", hasMore);
    }

    private List<ConversationResponse> emptyConversations(Long userId, List<Long> groupIds) {
        Set<String> populated = populatedConversationIds(userId, groupIds);
        List<ChatGroup> groups = groupService.getGroupsByIds(groupIds);
        Map<Long, Integer> memberCounts = groupService.getMemberCounts(groupIds);
        List<ConversationResponse> empty = new ArrayList<>();
        for (ChatGroup group : groups) {
            String conversationId = ChatMessage.buildGroupConversationId(group.getId());
            if (!populated.contains(conversationId)) {
                empty.add(toGroupConversationResponse(
                        group, null, memberCounts.getOrDefault(group.getId(), 0), 0L));
            }
        }
        for (Long friendId : friendService.getMutualFriendIds(userId)) {
            String conversationId = ChatMessage.buildConversationId(
                    String.valueOf(userId), String.valueOf(friendId));
            if (!populated.contains(conversationId)) empty.add(toFriendPreviewResponse(friendId));
        }
        retainVisibleDirectProfiles(userId, empty);
        fillOtherUserInfo(empty);
        empty.sort((left, right) -> {
            LocalDateTime leftTime = left.getLastMessageAt();
            LocalDateTime rightTime = right.getLastMessageAt();
            if (leftTime != null && rightTime != null) return rightTime.compareTo(leftTime);
            if (leftTime != null) return -1;
            if (rightTime != null) return 1;
            return nameOf(left).compareToIgnoreCase(nameOf(right));
        });
        return empty;
    }

    private Set<String> populatedConversationIds(Long userId, List<Long> groupIds) {
        String userIdString = String.valueOf(userId);
        Criteria direct = new Criteria().andOperator(
                Criteria.where("groupId").is(null),
                new Criteria().orOperator(
                        Criteria.where("senderId").is(userIdString),
                        Criteria.where("receiverId").is(userIdString)));
        Criteria scope = groupIds.isEmpty()
                ? direct : new Criteria().orOperator(direct, Criteria.where("groupId").in(groupIds));
        Query query = Query.query(new Criteria().andOperator(
                scope, Criteria.where("hiddenForUserIds").nin(userIdString)));
        return new HashSet<>(mongoTemplate.findDistinct(
                query, "conversationId", ChatMessage.class, String.class));
    }

    private Set<Long> blockedPeerIds(Long userId) {
        Set<Long> blocked = new HashSet<>();
        mongoTemplate.find(
                        Query.query(new Criteria().orOperator(
                                Criteria.where("ownerId").is(userId),
                                Criteria.where("targetId").is(userId))), UserBlock.class)
                .forEach(block -> {
                    Long peer = userId.equals(block.getOwnerId())
                            ? block.getTargetId() : block.getOwnerId();
                    if (peer != null && !userId.equals(peer)) blocked.add(peer);
                });
        return blocked;
    }

    private Set<String> activeSenderIds(Collection<String> senderIds) {
        if (senderIds == null || senderIds.isEmpty()) return Set.of();
        Map<Long, String> numeric = new HashMap<>();
        for (String senderId : senderIds) {
            if (senderId == null) continue;
            try {
                numeric.put(Long.valueOf(senderId), senderId);
            } catch (NumberFormatException ignored) {
                // Historical malformed sender identifiers are excluded from unread totals.
            }
        }
        if (numeric.isEmpty()) return Set.of();
        return userRepository.findActiveIdsByIdIn(numeric.keySet()).stream()
                .map(numeric::get).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map<String, Long> directUnreadCounts(Long userId, Collection<String> candidates) {
        if (candidates != null && candidates.isEmpty()) return Map.of();
        String userIdString = String.valueOf(userId);
        List<Criteria> clauses = new ArrayList<>();
        clauses.add(Criteria.where("receiverId").is(userIdString));
        clauses.add(Criteria.where("groupId").is(null));
        clauses.add(Criteria.where("hiddenForUserIds").nin(userIdString));
        if (candidates != null) clauses.add(Criteria.where("senderId").in(candidates));
        clauses.add(new Criteria().orOperator(
                Criteria.where("read").is(false), Criteria.where("read").exists(false)));
        Set<Long> blocked = blockedPeerIds(userId);
        if (!blocked.isEmpty()) clauses.add(Criteria.where("senderId").nin(
                blocked.stream().map(String::valueOf).toList()));

        Map<String, Long> raw = new HashMap<>();
        mongoTemplate.aggregate(
                        Aggregation.newAggregation(
                                Aggregation.match(new Criteria().andOperator(
                                        clauses.toArray(Criteria[]::new))),
                                Aggregation.group("senderId").count().as("count")),
                        ChatMessage.class, Document.class)
                .forEach(document -> raw.put(
                        document.getString("_id"), ((Number) document.get("count")).longValue()));
        Set<String> active = activeSenderIds(raw.keySet());
        raw.keySet().retainAll(active);
        return raw;
    }

    private Map<String, Long> groupUnreadCountsByIds(
            Collection<Long> groupIds, Long userId) {
        if (groupIds == null || groupIds.isEmpty()) return Map.of();
        List<Long> relevantGroupIds = groupIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (relevantGroupIds.isEmpty()) return Map.of();
        Map<Long, LocalDateTime> readTimes = new HashMap<>();
        for (int start = 0; start < relevantGroupIds.size(); start += GROUP_UNREAD_BATCH_SIZE) {
            List<Long> batch = relevantGroupIds.subList(
                    start, Math.min(start + GROUP_UNREAD_BATCH_SIZE, relevantGroupIds.size()));
            groupReadStatusRepository.findReadTimes(userId, batch).forEach(
                    status -> readTimes.put(status.getGroupId(), status.getLastReadAt()));
        }
        Set<String> blocked = blockedPeerIds(userId).stream()
                .map(String::valueOf).collect(Collectors.toSet());
        List<UnreadContribution> contributions = new ArrayList<>();

        for (int start = 0; start < relevantGroupIds.size(); start += GROUP_UNREAD_BATCH_SIZE) {
            List<Long> batch = relevantGroupIds.subList(
                    start, Math.min(start + GROUP_UNREAD_BATCH_SIZE, relevantGroupIds.size()));
            Criteria[] filters = batch.stream().map(groupId -> {
                List<Criteria> clauses = new ArrayList<>();
                clauses.add(Criteria.where("conversationId")
                        .is(ChatMessage.buildGroupConversationId(groupId)));
                clauses.add(Criteria.where("senderId").ne(String.valueOf(userId)));
                clauses.add(Criteria.where("hiddenForUserIds").nin(String.valueOf(userId)));
                if (!blocked.isEmpty()) clauses.add(Criteria.where("senderId").nin(blocked));
                LocalDateTime readAt = readTimes.get(groupId);
                if (readAt != null) clauses.add(Criteria.where("sentAt").gt(readAt));
                return new Criteria().andOperator(clauses.toArray(Criteria[]::new));
            }).toArray(Criteria[]::new);

            mongoTemplate.aggregate(
                            Aggregation.newAggregation(
                                    Aggregation.match(new Criteria().orOperator(filters)),
                                    Aggregation.group("conversationId", "senderId")
                                            .count().as("count")),
                            ChatMessage.class, Document.class)
                    .forEach(document -> {
                        Document id = document.get("_id", Document.class);
                        if (id != null) contributions.add(new UnreadContribution(
                                id.getString("conversationId"), id.getString("senderId"),
                                ((Number) document.get("count")).longValue()));
                    });
        }

        Set<String> active = activeSenderIds(contributions.stream()
                .map(UnreadContribution::senderId).collect(Collectors.toSet()));
        Map<String, Long> counts = new HashMap<>();
        contributions.stream().filter(value -> active.contains(value.senderId()))
                .forEach(value -> counts.merge(
                        value.conversationId(), value.count(), Long::sum));
        return counts;
    }

    private void retainVisibleDirectProfiles(Long userId, List<ConversationResponse> conversations) {
        List<Long> directIds = conversations.stream().filter(c -> !c.isGroup())
                .map(ConversationResponse::getOtherUserId).filter(Objects::nonNull).toList();
        Set<Long> visible = access.visibleProfileIds(userId, directIds);
        conversations.removeIf(c -> !c.isGroup() && !visible.contains(c.getOtherUserId()));
    }

    private ConversationResponse toDirectConversationResponse(
            String currentUserId, ChatMessage latest, Map<String, Long> unreadBySender) {
        boolean sender = currentUserId.equals(latest.getSenderId());
        String otherId = sender ? latest.getReceiverId() : latest.getSenderId();
        ConversationResponse response = new ConversationResponse();
        response.setGroup(false);
        response.setOtherUserId(Long.valueOf(otherId));
        response.setLastMessage(previewText(latest));
        response.setLastMessageAt(latest.getSentAt());
        response.setLastMessageTimeAgo(TimeAgoUtil.format(latest.getSentAt()));
        response.setUnreadCount(unreadBySender.getOrDefault(otherId, 0L));
        return response;
    }

    private ConversationResponse toFriendPreviewResponse(Long friendId) {
        ConversationResponse response = new ConversationResponse();
        response.setGroup(false);
        response.setOtherUserId(friendId);
        response.setHasConversation(false);
        return response;
    }

    private void fillOtherUserInfo(List<ConversationResponse> conversations) {
        Set<Long> ids = conversations.stream()
                .filter(c -> !c.isGroup() && c.getOtherUserId() != null
                        && c.getOtherUsername() == null)
                .map(ConversationResponse::getOtherUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        Map<Long, User> byId = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        Set<String> online = presenceService.onlineUsernames(
                byId.values().stream().map(User::getUsername).toList());
        conversations.forEach(conversation -> {
            if (conversation.isGroup()) return;
            User user = byId.get(conversation.getOtherUserId());
            if (user == null) return;
            conversation.setOtherUsername(user.getUsername());
            conversation.setOtherFullName(user.getFullName());
            conversation.setOtherAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
            conversation.setOnline(online.contains(user.getUsername()));
        });
    }

    private ConversationResponse toGroupConversationResponse(
            ChatGroup group, ChatMessage latest, int memberCount, long unreadCount) {
        ConversationResponse response = new ConversationResponse();
        response.setGroup(true);
        response.setGroupId(group.getId());
        response.setGroupName(group.getName());
        response.setOtherAvatarUrl(group.getAvatarUrl());
        response.setMemberCount(memberCount);
        response.setUnreadCount(unreadCount);
        if (latest != null) {
            response.setLastMessage(previewText(latest));
            response.setLastMessageAt(latest.getSentAt());
            response.setLastMessageTimeAgo(TimeAgoUtil.format(latest.getSentAt()));
        } else {
            response.setLastMessage("Nhóm vừa được tạo");
            response.setLastMessageAt(group.getCreatedAt());
            response.setLastMessageTimeAgo(TimeAgoUtil.format(group.getCreatedAt()));
        }
        return response;
    }

    private String nameOf(ConversationResponse conversation) {
        String name = conversation.isGroup()
                ? conversation.getGroupName() : conversation.getOtherFullName();
        return name == null ? "" : name;
    }

    private String previewText(ChatMessage message) {
        if ("CALL".equalsIgnoreCase(message.getMessageType())) {
            String call = "audio".equalsIgnoreCase(message.getCallType())
                    ? "Cuộc gọi thoại" : "Cuộc gọi video";
            if ("MISSED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " nhỡ";
            if ("REJECTED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " bị từ chối";
            return "☎ " + call;
        }
        if (message.getMediaType() != null) {
            if ("video".equalsIgnoreCase(message.getMediaType())) return "🎥 Video";
            if ("file".equalsIgnoreCase(message.getMediaType())) {
                return "📎 " + (message.getMediaName() != null
                        ? message.getMediaName() : "Tệp đính kèm");
            }
            return "📷 Ảnh";
        }
        return message.getContent() == null ? "" : message.getContent();
    }

    private String encodeActiveCursor(ChatMessage message) {
        String value = "A|" + message.getSentAt() + "|" + message.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeEmptyCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("E|" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private InboxCursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        if (encoded.length() > 512) throw new BadRequestException("Cursor hội thoại không hợp lệ");
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length == 2 && "E".equals(parts[0])) {
                int offset = Integer.parseInt(parts[1]);
                if (offset < 0 || offset > 1_000_000) throw new IllegalArgumentException();
                return new InboxCursor(true, null, null, offset);
            }
            if (parts.length == 3 && "A".equals(parts[0])) {
                LocalDateTime sentAt = LocalDateTime.parse(parts[1]);
                if (parts[2].isBlank() || parts[2].length() > 100) throw new IllegalArgumentException();
                return new InboxCursor(false, sentAt, parts[2], 0);
            }
            throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Cursor hội thoại không hợp lệ");
        }
    }

    private record InboxCursor(boolean emptyPhase, LocalDateTime sentAt, String id, int offset) {}
    private record UnreadContribution(String conversationId, String senderId, long count) {}
}
