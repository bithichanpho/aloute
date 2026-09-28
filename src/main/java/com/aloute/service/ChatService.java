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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ContentAccessService access;

    private final IChatMessageRepository chatMessageRepository;
    private final IUserRepository userRepository;
    private final IGroupReadStatusRepository groupReadStatusRepository;
    private final GroupService groupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FriendService friendService;
    private final PresenceService presenceService;
    private final MongoTemplate mongoTemplate;

    public ChatService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            GroupService groupService,
            SimpMessagingTemplate messagingTemplate,
            FriendService friendService,
            PresenceService presenceService,
            MongoTemplate mongoTemplate,
            ContentAccessService access) {
        this.access = access;

        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.groupService = groupService;
        this.messagingTemplate = messagingTemplate;
        this.friendService = friendService;
        this.presenceService = presenceService;
        this.mongoTemplate = mongoTemplate;
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

        long updated =
                mongoTemplate
                        .updateMulti(
                                Query.query(
                                        Criteria.where("receiverId")
                                                .is(String.valueOf(currentUserId))
                                                .and("senderId")
                                                .is(String.valueOf(otherUserId))
                                                .and("read")
                                                .is(false)
                                                .and("groupId")
                                                .is(null)),
                                new Update().set("read", true),
                                ChatMessage.class)
                        .getModifiedCount();
        if (updated == 0) {
            return;
        }

        userRepository
                .findById(otherUserId)
                .ifPresent(
                        otherUser ->
                                messagingTemplate.convertAndSendToUser(
                                        otherUser.getUsername(),
                                        "/queue/read-receipts",
                                        Map.of(
                                                "readerId",
                                                currentUserId,
                                                "readAt",
                                                LocalDateTime.now().toString())));
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
        long directUnread =
                chatMessageRepository.countByReceiverIdAndReadFalse(String.valueOf(userId));
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

        for (User member : userRepository.findAllById(groupService.getMemberIds(groupId))) {
            if (member.isActive()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUsername(), "/queue/messages", dto);
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
        GroupReadStatus status =
                groupReadStatusRepository
                        .findByGroupIdAndUserId(groupId, userId)
                        .orElseGet(() -> new GroupReadStatus(groupId, userId));
        status.setLastReadAt(LocalDateTime.now());
        groupReadStatusRepository.save(status);
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
                                                    .is(
                                                            ChatMessage.buildGroupConversationId(
                                                                    g.getId()));
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
                                Aggregation.match(
                                        Criteria.where("receiverId")
                                                .is(userIdStr)
                                                .and("read")
                                                .is(false)
                                                .and("groupId")
                                                .is(null)),
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
