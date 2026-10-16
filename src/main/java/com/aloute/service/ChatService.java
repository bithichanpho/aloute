package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.Story;
import com.aloute.model.document.StoryReply;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ContentAccessService access;

    private final IChatMessageRepository chatMessageRepository;
    private final IUserRepository userRepository;
    private final IGroupReadStatusRepository groupReadStatusRepository;
    private final GroupService groupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FriendService friendService;
    private final PresenceService presenceService;
    private final MongoTemplate mongoTemplate;
    private final PushNotificationService pushNotificationService;

    public ChatService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            GroupService groupService,
            SimpMessagingTemplate messagingTemplate,
            FriendService friendService,
            PresenceService presenceService,
            MongoTemplate mongoTemplate,
            ContentAccessService access,
            PushNotificationService pushNotificationService) {
        this.access = access;

        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.groupService = groupService;
        this.messagingTemplate = messagingTemplate;
        this.friendService = friendService;
        this.presenceService = presenceService;
        this.mongoTemplate = mongoTemplate;
        this.pushNotificationService = pushNotificationService;
    }

    public ChatMessageResponse sendMessage(
            Long senderId, Long receiverId, String content, String clientId) {
        return sendMessage(senderId, receiverId, content, clientId, null, null, null, null);
    }

    public ChatMessageResponse sendMessage(
            Long senderId,
            Long receiverId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType,
            String mediaName,
            Long mediaSize) {
        return sendMessageInternal(
                senderId, receiverId, content, clientId, mediaUrl, mediaType, mediaName, mediaSize, false);
    }

    public ChatMessageResponse sendStoryReply(
            Long senderId, Long receiverId, String content, String storyId,
            String storyContent, String storyImageUrl, String storyVideoUrl) {
        ChatMessageResponse response = sendMessageInternal(
                senderId, receiverId, content, "story:" + storyId, null, null, null, null, true);
        ChatMessage message = chatMessageRepository.findById(response.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tin nhắn"));
        message.setMessageType("STORY_REPLY");
        message.setStoryId(storyId);
        message.setStoryContent(storyContent);
        message.setStoryImageUrl(storyImageUrl);
        message.setStoryVideoUrl(storyVideoUrl);
        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse enriched = toResponse(saved);
        enriched.setClientId("story:" + storyId);
        User sender = userRepository.findById(senderId).orElseThrow();
        User receiver = userRepository.findById(receiverId).orElseThrow();
        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", enriched);
        messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", enriched);
        sendChatPushSafely(
                receiver.getId(),
                displayName(sender),
                notificationPreview(enriched.getContent(), enriched.getMediaUrl()),
                "/chat?userId=" + sender.getId());
        return enriched;
    }

    private ChatMessageResponse sendMessageInternal(
            Long senderId,
            Long receiverId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType,
            String mediaName,
            Long mediaSize,
            boolean storyReply) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (senderId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự nhắn tin cho chính mình");
        }

        access.requireProfile(senderId, receiverId);
        User sender =
                userRepository
                        .findById(senderId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người gửi"));
        if (!sender.isActive()) throw new UnauthorizedException("Tài khoản đã bị vô hiệu hóa");
        User receiver =
                userRepository
                        .findById(receiverId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        if (!storyReply && receiver.isPrivateAccount() && !friendService.isFriend(senderId, receiverId)) {
            throw new BadRequestException(
                    "Tài khoản này ở chế độ riêng tư — hai bạn cần theo dõi lẫn nhau mới nhắn tin"
                            + " được");
        }

        ChatMessage message = new ChatMessage();
        message.setConversationId(
                ChatMessage.buildConversationId(
                        String.valueOf(senderId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(senderId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved, sender);
        dto.setClientId(clientId);

        if (!storyReply) {
            messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
            messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", dto);
            sendChatPushSafely(
                    receiver.getId(),
                    displayName(sender),
                    notificationPreview(dto.getContent(), dto.getMediaUrl()),
                    "/chat?userId=" + sender.getId());
        }

        return dto;
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId) {
        return getMessages(currentUserId, otherUserId, 0);
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId, int page) {
        access.requireProfile(currentUserId, otherUserId);
        String conversationId =
                ChatMessage.buildConversationId(
                        String.valueOf(currentUserId), String.valueOf(otherUserId));
        return history(conversationId, page, currentUserId);
    }

    public void markAsRead(Long currentUserId, Long otherUserId) {
        access.requireProfile(currentUserId, otherUserId);
        String currentId = String.valueOf(currentUserId);
        String otherId = String.valueOf(otherUserId);
        Criteria unreadCriteria = new Criteria().andOperator(
                Criteria.where("receiverId").is(currentId),
                Criteria.where("senderId").is(otherId),
                Criteria.where("groupId").is(null),
                new Criteria().orOperator(
                        Criteria.where("read").is(false), Criteria.where("read").exists(false)));
        List<ChatMessage> recentUnread = mongoTemplate.find(
                Query.query(unreadCriteria).with(Sort.by(Sort.Direction.DESC, "sentAt")).limit(300),
                ChatMessage.class);
        long updated = mongoTemplate.updateMulti(Query.query(unreadCriteria),
                new Update().set("read", true), ChatMessage.class).getModifiedCount();
        if (updated == 0) return;

        List<String> messageIds = recentUnread.stream().map(ChatMessage::getId)
                .filter(java.util.Objects::nonNull).toList();
        userRepository.findById(otherUserId).ifPresent(otherUser -> {
            Map<String, Object> receipt = new java.util.HashMap<>();
            receipt.put("readerId", currentUserId);
            receipt.put("otherUserId", otherUserId);
            receipt.put("readAt", LocalDateTime.now().toString());
            receipt.put("status", "READ");
            receipt.put("messageIds", messageIds);
            messagingTemplate.convertAndSendToUser(otherUser.getUsername(), "/queue/read-receipts", receipt);
        });
    }

    /** Called only after an authenticated recipient client receives a persisted WebSocket message. */
    public void markAsDelivered(Long recipientId, String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tin nhắn"));
        String recipient = String.valueOf(recipientId);
        String senderId = message.getSenderId();
        if (senderId == null || senderId.equals(recipient)) return;
        LocalDateTime now = LocalDateTime.now();

        if (message.getGroupId() != null) {
            Long groupId = message.getGroupId();
            if (!groupService.isMember(groupId, recipientId)) {
                throw new ForbiddenException("Bạn không phải thành viên của nhóm này");
            }
            Query q = Query.query(Criteria.where("_id").is(messageId)
                    .and("groupId").is(groupId)
                    .and("senderId").ne(recipient)
                    .and("deliveredToUserIds").nin(recipient));
            var result = mongoTemplate.updateFirst(q,
                    new Update().addToSet("deliveredToUserIds", recipient).set("delivered", true),
                    ChatMessage.class);
            if (result.getModifiedCount() == 0) return;
            ChatMessage updated = chatMessageRepository.findById(messageId).orElse(message);
            userRepository.findById(Long.valueOf(senderId)).ifPresent(sender -> {
                Map<String, Object> receipt = new java.util.HashMap<>();
                receipt.put("messageId", messageId);
                receipt.put("senderId", Long.valueOf(senderId));
                receipt.put("recipientId", recipientId);
                receipt.put("groupId", groupId);
                receipt.put("deliveredCount", updated.getDeliveredToUserIds().size());
                receipt.put("status", "DELIVERED");
                receipt.put("deliveredAt", now.toString());
                messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/delivery-receipts", receipt);
            });
            return;
        }

        if (message.getReceiverId() == null || !message.getReceiverId().equals(recipient)) {
            throw new ForbiddenException("Bạn không phải người nhận tin nhắn này");
        }
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(messageId)
                        .and("receiverId").is(recipient).and("delivered").is(false)),
                new Update().set("delivered", true).set("deliveredAt", now), ChatMessage.class);
        if (result.getModifiedCount() == 0) return;
        userRepository.findById(Long.valueOf(senderId)).ifPresent(sender -> {
            Map<String, Object> receipt = new java.util.HashMap<>();
            receipt.put("messageId", messageId);
            receipt.put("senderId", Long.valueOf(senderId));
            receipt.put("receiverId", recipientId);
            receipt.put("delivered", true);
            receipt.put("deliveredCount", 1);
            receipt.put("status", "DELIVERED");
            receipt.put("deliveredAt", now.toString());
            messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/delivery-receipts", receipt);
        });
    }

    public ChatMessageResponse createMissedCallMessage(
            Long callerId, Long receiverId, String callId, String callType) {
        if (callerId == null || receiverId == null || callId == null || callId.isBlank()) {
            return null;
        }

        var existed = chatMessageRepository.findByCallId(callId);
        if (existed.isPresent()) {
            return toResponse(existed.get());
        }

        User caller =
                userRepository
                        .findById(callerId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người gọi"));
        User receiver =
                userRepository
                        .findById(receiverId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        ChatMessage message = new ChatMessage();
        message.setConversationId(
                ChatMessage.buildConversationId(
                        String.valueOf(callerId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(callerId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent("");
        message.setMessageType("CALL");
        message.setCallId(callId);
        message.setCallType("audio".equalsIgnoreCase(callType) ? "audio" : "video");
        message.setCallStatus("MISSED");

        message.setRead(false);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved, caller);

        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
        messagingTemplate.convertAndSendToUser(caller.getUsername(), "/queue/messages", dto);
        return dto;
    }

    public long countUnreadTotal(Long userId) {
        String recipientId = String.valueOf(userId);
        Criteria directUnreadCriteria = new Criteria().andOperator(
                Criteria.where("receiverId").is(recipientId),
                Criteria.where("groupId").is(null),
                new Criteria().orOperator(
                        Criteria.where("read").is(false), Criteria.where("read").exists(false)));
        long directUnread = mongoTemplate.count(Query.query(directUnreadCriteria), ChatMessage.class);
        long groupUnread =
                groupUnreadCounts(groupService.getMyGroups(userId), userId).values().stream()
                        .mapToLong(Long::longValue)
                        .sum();
        return directUnread + groupUnread;
    }

    public ChatMessageResponse sendGroupMessage(
            Long senderId, Long groupId, String content, String clientId) {
        return sendGroupMessage(senderId, groupId, content, clientId, null, null, null, null);
    }

    public ChatMessageResponse sendGroupMessage(
            Long senderId,
            Long groupId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType) {
        return sendGroupMessage(
                senderId, groupId, content, clientId, mediaUrl, mediaType, null, null);
    }

    public ChatMessageResponse sendGroupMessage(
            Long senderId,
            Long groupId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType,
            String mediaName,
            Long mediaSize) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (!groupService.isMember(groupId, senderId)) {
            throw new ForbiddenException("Bạn không phải thành viên của nhóm này");
        }

        User sender =
                userRepository
                        .findById(senderId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người gửi"));
        if (!sender.isActive()) throw new UnauthorizedException("Tài khoản đã bị vô hiệu hóa");

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildGroupConversationId(groupId));
        message.setSenderId(String.valueOf(senderId));
        message.setGroupId(groupId);
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved, sender);
        dto.setClientId(clientId);

        String groupName = "nhóm";
        try {
            var group = groupService.getGroupDetail(groupId, senderId);
            if (group != null && group.getName() != null && !group.getName().isBlank()) groupName = group.getName();
        } catch (RuntimeException ignored) {
            // The persisted message and WebSocket delivery must not depend on push metadata.
        }
        for (User member : userRepository.findAllById(groupService.getMemberIds(groupId))) {
            if (member.isActive()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUsername(), "/queue/messages", dto);
                if (!member.getId().equals(senderId)) {
                    sendChatPushSafely(
                            member.getId(),
                            "Tin nhắn trong " + groupName,
                            displayName(sender) + ": " + notificationPreview(dto.getContent(), dto.getMediaUrl()),
                            "/chat?groupId=" + groupId);
                }
            }
        }

        return dto;
    }

    public List<ChatMessageResponse> getGroupMessages(Long groupId, Long currentUserId) {
        return getGroupMessages(groupId, currentUserId, 0);
    }

    public List<ChatMessageResponse> getGroupMessages(Long groupId, Long currentUserId, int page) {
        if (!groupService.isMember(groupId, currentUserId)) {
            throw new ForbiddenException("Bạn không phải thành viên của nhóm này");
        }
        String conversationId = ChatMessage.buildGroupConversationId(groupId);
        return history(conversationId, page, currentUserId);
    }

    public void markGroupAsRead(Long groupId, Long userId) {
        if (!groupService.isMember(groupId, userId)) {
            throw new ForbiddenException("Bạn không phải thành viên của nhóm này");
        }
        String readerId = String.valueOf(userId);
        GroupReadStatus status = groupReadStatusRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseGet(() -> new GroupReadStatus(groupId, userId));
        LocalDateTime readAt = LocalDateTime.now();

        Criteria unreadForReader = Criteria.where("groupId").is(groupId)
                .and("senderId").ne(readerId).and("readByUserIds").nin(readerId);
        Query receiptQuery = Query.query(unreadForReader).with(Sort.by(Sort.Direction.DESC, "sentAt")).limit(250);
        List<ChatMessage> receiptCandidates = mongoTemplate.find(receiptQuery, ChatMessage.class);

        mongoTemplate.updateMulti(Query.query(unreadForReader),
                new Update().addToSet("readByUserIds", readerId), ChatMessage.class);
        status.setLastReadAt(readAt);
        groupReadStatusRepository.save(status);

        if (receiptCandidates.isEmpty()) return;
        Map<String, List<String>> idsBySender = new java.util.HashMap<>();
        for (ChatMessage candidate : receiptCandidates) {
            if (candidate.getSenderId() == null || candidate.getId() == null) continue;
            idsBySender.computeIfAbsent(candidate.getSenderId(), ignored -> new java.util.ArrayList<>())
                    .add(candidate.getId());
        }
        Set<Long> senderIds = idsBySender.keySet().stream().map(Long::valueOf).collect(Collectors.toSet());
        Map<Long, User> usersById = new java.util.HashMap<>();
        userRepository.findAllById(senderIds).forEach(user -> usersById.put(user.getId(), user));
        idsBySender.forEach((senderId, messageIds) -> {
            User sender = usersById.get(Long.valueOf(senderId));
            if (sender == null || messageIds.isEmpty()) return;
            Map<String, Object> receipt = new java.util.HashMap<>();
            receipt.put("readerId", userId);
            receipt.put("groupId", groupId);
            receipt.put("readAt", readAt.toString());
            receipt.put("status", "READ");
            receipt.put("messageIds", messageIds);
            messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/read-receipts", receipt);
        });
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

        Criteria[] filters =
                groups.stream()
                        .map(
                                g -> {
                                    Criteria c =
                                            Criteria.where("conversationId")
                                                    .is(ChatMessage.buildGroupConversationId(g.getId()))
                                                    .and("senderId").ne(String.valueOf(userId));
                                    if (readTimes.containsKey(g.getId())) {
                                        c = c.and("sentAt").gt(readTimes.get(g.getId()));
                                    }
                                    return c;
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
        mongoTemplate
                .aggregate(
                        Aggregation.newAggregation(
                                Aggregation.match(new Criteria().andOperator(
                                        Criteria.where("receiverId").is(userIdStr),
                                        Criteria.where("groupId").is(null),
                                        new Criteria().orOperator(
                                                Criteria.where("read").is(false),
                                                Criteria.where("read").exists(false)))),
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

    public void deleteMessage(String messageId, Long currentUserId, boolean forEveryone) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Tin nhắn không tồn tại"));
        String userId = String.valueOf(currentUserId);
        boolean participant = userId.equals(message.getSenderId()) || userId.equals(message.getReceiverId());
        if (message.getGroupId() != null) participant = groupService.isMember(message.getGroupId(), currentUserId);
        if (!participant) throw new ForbiddenException("Bạn không có quyền với tin nhắn này");

        if (!forEveryone) {
            message.getHiddenForUserIds().add(userId);
            chatMessageRepository.save(message);
            return;
        }
        if (!userId.equals(message.getSenderId())) {
            throw new ForbiddenException("Chỉ người gửi mới có thể thu hồi tin nhắn với mọi người");
        }
        chatMessageRepository.delete(message);
        Map<String, Object> event = Map.of("messageId", messageId, "forEveryone", true);
        if (message.getGroupId() != null) {
            for (User member : userRepository.findAllById(groupService.getMemberIds(message.getGroupId()))) {
                messagingTemplate.convertAndSendToUser(member.getUsername(), "/queue/message-deletions", event);
            }
        } else {
            Set<Long> ids = Set.of(Long.valueOf(message.getSenderId()), Long.valueOf(message.getReceiverId()));
            for (User member : userRepository.findAllById(ids)) {
                messagingTemplate.convertAndSendToUser(member.getUsername(), "/queue/message-deletions", event);
            }
        }
    }

    private List<ChatMessageResponse> history(String conversationId, int page, Long currentUserId) {
        int safePage = Math.max(0, page);
        var messages =
                new java.util.ArrayList<>(
                        chatMessageRepository.findByConversationIdOrderBySentAtDesc(
                                conversationId,
                                PageRequest.of(
                                        safePage,
                                        50,
                                        Sort.by(Sort.Direction.DESC, "sentAt", "id"))));
        java.util.Collections.reverse(messages);

        restoreLegacyStoryReplies(messages);
        messages.removeIf(message -> message.getHiddenForUserIds().contains(String.valueOf(currentUserId)));
        var senderIds =
                messages.stream()
                        .map(ChatMessage::getSenderId)
                        .filter(java.util.Objects::nonNull)
                        .map(Long::valueOf)
                        .collect(Collectors.toSet());
        var users =
                userRepository.findAllById(senderIds).stream()
                        .collect(
                                Collectors.toMap(
                                        User::getId, java.util.function.Function.identity()));

        return messages.stream()
                .map(
                        m ->
                                toResponse(
                                        m,
                                        users.get(
                                                m.getSenderId() != null
                                                        ? Long.valueOf(m.getSenderId())
                                                        : null)))
                .collect(Collectors.toList());
    }

    private void restoreLegacyStoryReplies(List<ChatMessage> messages) {
        List<ChatMessage> legacy = messages.stream()
                .filter(message -> message.getStoryId() == null)
                .filter(message -> message.getContent() != null && message.getContent().startsWith("Đã trả lời tin “"))
                .toList();
        if (legacy.isEmpty()) return;

        Set<Long> participantIds = messages.stream()
                .flatMap(message -> java.util.stream.Stream.of(message.getSenderId(), message.getReceiverId()))
                .filter(java.util.Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        List<StoryReply> replies = mongoTemplate.find(
                Query.query(Criteria.where("senderId").in(participantIds).and("ownerId").in(participantIds)),
                StoryReply.class);
        if (replies.isEmpty()) return;

        Map<String, Story> storyCache = new java.util.HashMap<>();
        List<ChatMessage> changed = new java.util.ArrayList<>();
        for (ChatMessage message : legacy) {
            StoryReply match = replies.stream()
                    .filter(reply -> String.valueOf(reply.getSenderId()).equals(message.getSenderId()))
                    .filter(reply -> String.valueOf(reply.getOwnerId()).equals(message.getReceiverId()))
                    .filter(reply -> message.getContent().endsWith(": " + reply.getContent()))
                    .min(java.util.Comparator.comparingLong(reply -> legacyReplyDistance(message, reply)))
                    .orElse(null);
            if (match == null) continue;
            Story story = storyCache.computeIfAbsent(match.getStoryId(), id -> mongoTemplate.findById(id, Story.class));
            if (story == null) continue;
            message.setMessageType("STORY_REPLY");
            message.setStoryId(story.getId());
            message.setStoryContent(story.getContent());
            message.setStoryImageUrl(story.getImageUrl());
            message.setStoryVideoUrl(story.getVideoUrl());
            message.setContent(match.getContent());
            changed.add(message);
        }
        if (!changed.isEmpty()) chatMessageRepository.saveAll(changed);
    }

    private long legacyReplyDistance(ChatMessage message, StoryReply reply) {
        if (message.getSentAt() == null || reply.getCreatedAt() == null) return Long.MAX_VALUE;
        java.time.Instant sent = message.getSentAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
        return Math.abs(java.time.Duration.between(sent, reply.getCreatedAt()).toMillis());
    }

    private void sendChatPushSafely(Long receiverId, String title, String body, String relativeUrl) {
        try {
            pushNotificationService.sendChatMessageToUser(receiverId, title, body, relativeUrl);
        } catch (Exception error) {
            log.debug("FCM chat push skipped (receiverId={}, cause={})", receiverId,
                    error.getClass().getSimpleName());
        }
    }

    private String displayName(User user) {
        if (user == null) return "Bạn bè";
        if (user.getFullName() != null && !user.getFullName().isBlank()) return user.getFullName();
        return user.getUsername() == null ? "Bạn bè" : user.getUsername();
    }

    private String notificationPreview(String content, String mediaUrl) {
        String preview = content == null ? "" : content.trim();
        if (preview.isBlank() && mediaUrl != null && !mediaUrl.isBlank()) preview = "Đã gửi tệp đính kèm";
        if (preview.isBlank()) preview = "Bạn có tin nhắn mới";
        return preview.length() > 140 ? preview.substring(0, 137) + "…" : preview;
    }

    private ChatMessageResponse toResponse(ChatMessage m) {
        User sender =
                m.getSenderId() == null
                        ? null
                        : userRepository.findById(Long.valueOf(m.getSenderId())).orElse(null);
        return toResponse(m, sender);
    }

    private ChatMessageResponse toResponse(ChatMessage m, User sender) {
        ChatMessageResponse dto = new ChatMessageResponse();
        dto.setId(m.getId());
        dto.setSenderId(Long.valueOf(m.getSenderId()));
        dto.setReceiverId(m.getReceiverId() != null ? Long.valueOf(m.getReceiverId()) : null);
        dto.setGroupId(m.getGroupId());
        dto.setContent(m.getContent());
        dto.setMediaUrl(m.getMediaUrl());
        dto.setMediaType(m.getMediaType());
        dto.setMediaName(m.getMediaName());
        dto.setMediaSize(m.getMediaSize());
        dto.setMessageType(m.getMessageType());
        dto.setCallId(m.getCallId());
        dto.setCallType(m.getCallType());
        dto.setCallStatus(m.getCallStatus());
        dto.setStoryId(m.getStoryId());
        dto.setStoryContent(m.getStoryContent());
        dto.setStoryImageUrl(m.getStoryImageUrl());
        dto.setStoryVideoUrl(m.getStoryVideoUrl());
        dto.setRead(m.isRead());
        dto.setDelivered(m.isDelivered() || (m.getGroupId() != null && !m.getDeliveredToUserIds().isEmpty()));
        dto.setDeliveredAt(m.getDeliveredAt());
        dto.setDeliveredCount(m.getGroupId() != null ? m.getDeliveredToUserIds().size() : (m.isDelivered() ? 1 : 0));
        dto.setReadCount(m.getGroupId() != null ? m.getReadByUserIds().size() : (m.isRead() ? 1 : 0));
        dto.setSentAt(m.getSentAt() != null ? m.getSentAt() : LocalDateTime.now());
        dto.setTimeAgo(TimeAgoUtil.format(m.getSentAt()));

        if (sender != null) {
            dto.setSenderUsername(sender.getUsername());
            dto.setSenderFullName(sender.getFullName());
            dto.setSenderAvatarUrl(AvatarUtil.resolve(sender.getAvatarUrl()));
        }
        return dto;
    }
}
