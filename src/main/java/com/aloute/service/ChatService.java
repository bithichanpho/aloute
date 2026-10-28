package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ChatCursorPage;
import com.aloute.dto.response.ConversationCursorPage;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatService implements com.aloute.messaging.api.MissedCallHistory {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ContentAccessService access;

    private final IChatMessageRepository chatMessageRepository;
    private final IUserRepository userRepository;
    private final IGroupReadStatusRepository groupReadStatusRepository;
    private final GroupService groupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FriendService friendService;
    private final PostgresDocumentStore documentStore;
    private final PushNotificationService pushNotificationService;
    private final ConversationService conversationService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatHistoryService chatHistoryService;
    private final MediaUploadModerationService mediaUploadModeration;
    private final MediaAssetLifecycleService mediaAssets;
    private final com.aloute.messaging.api.CallHistoryAuthorization callHistoryAuthorization;

    @Autowired
    public ChatService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            GroupService groupService,
            SimpMessagingTemplate messagingTemplate,
            FriendService friendService,
            PostgresDocumentStore documentStore,
            ContentAccessService access,
            PushNotificationService pushNotificationService,
            ConversationService conversationService,
            ChatMessageMapper chatMessageMapper,
            ChatHistoryService chatHistoryService,
            MediaUploadModerationService mediaUploadModeration,
            MediaAssetLifecycleService mediaAssets, com.aloute.messaging.api.CallHistoryAuthorization callHistoryAuthorization) {
        this.callHistoryAuthorization = callHistoryAuthorization;
        this.access = access;

        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.groupService = groupService;
        this.messagingTemplate = messagingTemplate;
        this.friendService = friendService;
        this.documentStore = documentStore;
        this.pushNotificationService = pushNotificationService;
        this.conversationService = conversationService;
        this.chatMessageMapper = chatMessageMapper;
        this.chatHistoryService = chatHistoryService;
        this.mediaUploadModeration = mediaUploadModeration;
        this.mediaAssets = mediaAssets;
    }

    /** Compatibility constructor; production requires the durable history policy above. */
    public ChatService(IChatMessageRepository messages, IUserRepository users,
            IGroupReadStatusRepository readStatus, GroupService groups, SimpMessagingTemplate messaging,
            FriendService friends, PostgresDocumentStore documents, ContentAccessService access,
            PushNotificationService push, ConversationService conversations, ChatMessageMapper mapper,
            ChatHistoryService history, MediaUploadModerationService moderation, MediaAssetLifecycleService media) {
        this(messages, users, readStatus, groups, messaging, friends, documents, access, push,
                conversations, mapper, history, moderation, media, null);
    }

    /** Backward-compatible constructor for focused unit tests. Spring uses the @Autowired constructor above. */
    public ChatService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            GroupService groupService,
            SimpMessagingTemplate messagingTemplate,
            FriendService friendService,
            PresenceService presenceService,
            PostgresDocumentStore documentStore,
            ContentAccessService access,
            PushNotificationService pushNotificationService) {
        this(chatMessageRepository, userRepository, groupReadStatusRepository, groupService,
                messagingTemplate, friendService, documentStore, access,
                pushNotificationService,
                new ConversationService(userRepository, groupReadStatusRepository, groupService,
                        friendService, presenceService, documentStore, access),
                new ChatMessageMapper(userRepository),
                new ChatHistoryService(chatMessageRepository, userRepository, new ChatMessageMapper(userRepository)),
                null,
                null);
    }

    @Transactional
    public ChatMessageResponse sendMessage(
            Long senderId, Long receiverId, String content, String clientId) {
        return sendMessage(senderId, receiverId, content, clientId, null, null, null, null);
    }

    @Transactional
    public ChatMessageResponse sendMessage(
            Long senderId,
            Long receiverId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType,
            String mediaName,
            Long mediaSize) {
        String normalizedClientId = normalizeClientId(clientId);
        ChatMessageResponse previous = findExistingMessage(senderId, normalizedClientId);
        if (previous != null) return previous;
        return sendMessageInternal(
                senderId, receiverId, content, normalizedClientId, mediaUrl, mediaType, mediaName, mediaSize);
    }

    /** True when a previous WebSocket retry already persisted this sender/clientId pair. */
    public boolean hasMessageForClientId(Long senderId, String clientId) {
        String normalized = normalizeClientId(clientId);
        return normalized != null && chatMessageRepository.existsBySenderIdAndClientId(
                String.valueOf(senderId), normalized);
    }

    /** Returns the canonical persisted message so a concurrent duplicate can receive an ACK. */
    public ChatMessageResponse findMessageForClientId(Long senderId, String clientId) {
        return findExistingMessage(senderId, normalizeClientId(clientId));
    }

    private ChatMessageResponse findExistingMessage(Long senderId, String clientId) {
        if (senderId == null || clientId == null) return null;
        return chatMessageRepository.findBySenderIdAndClientId(String.valueOf(senderId), clientId)
                .map(this::toResponse)
                .map(response -> {
                    response.setClientId(clientId);
                    return response;
                })
                .orElse(null);
    }

    private String normalizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        String normalized = clientId.trim();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private ChatMessageResponse sendMessageInternal(
            Long senderId,
            Long receiverId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType,
            String mediaName,
            Long mediaSize) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (senderId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự nhắn tin cho chính mình");
        }
        assertMediaAllowedForChat(mediaUrl, String.valueOf(senderId));

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

        if (receiver.isPrivateAccount() && !access.canMessageAccount(senderId, receiverId)) {
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
        message.setClientId(normalizeClientId(clientId));
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        attachChatMedia(saved, senderId);
        ChatMessageResponse dto = toResponse(saved, sender);
        dto.setClientId(clientId);

        afterCommitSafely("direct chat delivery", () -> {
            messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
            messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", dto);
            sendChatPushSafely(
                    receiver.getId(),
                    displayName(sender),
                    notificationPreview(dto.getContent(), dto.getMediaUrl()),
                    "/chat?userId=" + sender.getId());
        });

        return dto;
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId) {
        return getMessages(currentUserId, otherUserId, 0);
    }

    private void requireDirectConversationAccess(Long currentUserId, Long otherUserId) {
        access.requireProfile(currentUserId, otherUserId);
        if (!access.canMessageAccount(currentUserId, otherUserId)) {
            throw new ForbiddenException(
                    "Tài khoản này ở chế độ riêng tư — hai bạn cần theo dõi lẫn nhau để xem hoặc nhắn tin");
        }
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId, int page) {
        requireDirectConversationAccess(currentUserId, otherUserId);
        String conversationId =
                ChatMessage.buildConversationId(
                        String.valueOf(currentUserId), String.valueOf(otherUserId));
        return chatHistoryService.history(conversationId, page, currentUserId);
    }

    public ChatCursorPage getMessagesByCursor(
            Long currentUserId, Long otherUserId, String beforeId, int requestedSize) {
        requireDirectConversationAccess(currentUserId, otherUserId);
        String conversationId = ChatMessage.buildConversationId(
                String.valueOf(currentUserId), String.valueOf(otherUserId));
        return conversationHistoryByCursor(conversationId, currentUserId, beforeId, requestedSize);
    }

    public List<ChatMessageResponse> searchDirectMessages(
            Long currentUserId,
            Long otherUserId,
            String keyword,
            String beforeId,
            int size,
            boolean mediaOnly) {
        requireDirectConversationAccess(currentUserId, otherUserId);
        String conversationId = ChatMessage.buildConversationId(
                String.valueOf(currentUserId), String.valueOf(otherUserId));
        return searchConversation(conversationId, currentUserId, keyword, beforeId, size, mediaOnly);
    }

    public List<ChatMessageResponse> searchGroupMessages(
            Long currentUserId,
            Long groupId,
            String keyword,
            String beforeId,
            int size,
            boolean mediaOnly) {
        if (!groupService.isMember(groupId, currentUserId)) {
            throw new ForbiddenException("Bạn không phải thành viên nhóm");
        }
        return searchConversation(
                ChatMessage.buildGroupConversationId(groupId),
                currentUserId,
                keyword,
                beforeId,
                size,
                mediaOnly);
    }

    private List<ChatMessageResponse> searchConversation(
            String conversationId,
            Long currentUserId,
            String keyword,
            String beforeId,
            int requestedSize,
            boolean mediaOnly) {
        int size = Math.max(1, Math.min(50, requestedSize));
        java.util.List<Criteria> clauses = new java.util.ArrayList<>();
        clauses.add(Criteria.where("conversationId").is(conversationId));
        clauses.add(Criteria.where("hiddenForUserIds").nin(String.valueOf(currentUserId)));
        String term = null;
        if (mediaOnly) {
            clauses.add(Criteria.where("mediaUrl").nin(null, ""));
        } else {
            term = keyword == null ? "" : keyword.trim();
            if (term.isBlank()) throw new BadRequestException("Từ khóa tìm kiếm không được để trống");
        }
        if (beforeId != null && !beforeId.isBlank()) clauses.add(Criteria.where("_id").lt(beforeId));
        Query query = Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)));
        if (!mediaOnly) {
            query.addCriteria(com.aloute.persistence.postgres.document.TextCriteria
                    .forDefaultLanguage().matching(term));
        }
        query.with(Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("_id"))).limit(size);
        List<ChatMessage> messages = documentStore.find(query, ChatMessage.class);
        java.util.Set<Long> senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .filter(java.util.Objects::nonNull)
                .map(Long::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, User> senders = userRepository.findAllById(senderIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        return messages.stream()
                .map(message -> toResponse(
                        message,
                        message.getSenderId() == null
                                ? null
                                : senders.get(Long.valueOf(message.getSenderId()))))
                .toList();
    }

    @Transactional
    public void markAsRead(Long currentUserId, Long otherUserId) {
        if (currentUserId == null || otherUserId == null || currentUserId.equals(otherUserId)) return;
        User otherAccount = userRepository.findById(otherUserId).orElse(null);
        if (otherAccount != null && otherAccount.isPrivateAccount()
                && !friendService.isFriend(currentUserId, otherUserId)) return;
        // Reading an existing conversation must remain possible after either side blocks the other
        // or one account is deactivated; otherwise its unread badge can never be cleared.
        String currentId = String.valueOf(currentUserId);
        String otherId = String.valueOf(otherUserId);
        Criteria unreadCriteria = new Criteria().andOperator(
                Criteria.where("receiverId").is(currentId),
                Criteria.where("senderId").is(otherId),
                Criteria.where("groupId").is(null),
                Criteria.where("hiddenForUserIds").nin(currentId),
                new Criteria().orOperator(
                        Criteria.where("read").is(false), Criteria.where("read").exists(false)));
        List<ChatMessage> recentUnread = documentStore.find(
                Query.query(unreadCriteria).with(Sort.by(Sort.Direction.DESC, "sentAt")).limit(300),
                ChatMessage.class);
        long updated = documentStore.updateMulti(Query.query(unreadCriteria),
                new Update().set("read", true), ChatMessage.class).getModifiedCount();
        if (updated == 0) return;

        List<String> messageIds = recentUnread.stream().map(ChatMessage::getId)
                .filter(java.util.Objects::nonNull).toList();
        if (!access.profileVisible(currentUserId, otherUserId)) return;
        userRepository.findById(otherUserId).filter(User::isActive).ifPresent(otherUser -> {
            Map<String, Object> receipt = new java.util.HashMap<>();
            receipt.put("readerId", currentUserId);
            receipt.put("otherUserId", otherUserId);
            receipt.put("readAt", LocalDateTime.now().toString());
            receipt.put("status", "READ");
            receipt.put("messageIds", messageIds);
            afterCommitSafely("direct read receipt", () ->
                    messagingTemplate.convertAndSendToUser(
                            otherUser.getUsername(), "/queue/read-receipts", receipt));
        });
    }

    /** Called only after an authenticated recipient client receives a persisted WebSocket message. */
    @Transactional
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
            var result = documentStore.updateFirst(q,
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
                afterCommitSafely("group delivery receipt", () ->
                        messagingTemplate.convertAndSendToUser(
                                sender.getUsername(), "/queue/delivery-receipts", receipt));
            });
            return;
        }

        if (message.getReceiverId() == null || !message.getReceiverId().equals(recipient)) {
            throw new ForbiddenException("Bạn không phải người nhận tin nhắn này");
        }
        var result = documentStore.updateFirst(
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
            afterCommitSafely("direct delivery receipt", () ->
                    messagingTemplate.convertAndSendToUser(
                            sender.getUsername(), "/queue/delivery-receipts", receipt));
        });
    }

    @Transactional
    public ChatMessageResponse createMissedCallMessage(
            Long callerId, Long receiverId, String callId, String callType) {
        validateCallHistoryParticipants(callerId, receiverId, callId);
        if (callHistoryAuthorization != null
                && !callHistoryAuthorization.missedFor(callId, callerId, receiverId)) {
            throw new ForbiddenException("Không thể ghi lịch sử cho cuộc gọi chưa được xác thực");
        }
        return persistCallHistoryMessage(callerId, receiverId, callId, callType, "MISSED", 0L);
    }

    /** Writes the record from server-authoritative terminal call state, including answered duration. */
    @Override
    @Transactional
    public ChatMessageResponse createCallHistoryMessage(Long callerId, Long receiverId, String callId) {
        validateCallHistoryParticipants(callerId, receiverId, callId);
        if (callHistoryAuthorization == null) {
            throw new ForbiddenException("Không có nguồn xác thực lịch sử cuộc gọi");
        }
        var terminal = callHistoryAuthorization.terminalCallFor(callId, callerId, receiverId)
                .orElseThrow(() -> new ForbiddenException("Cuộc gọi chưa kết thúc hoặc không thuộc hai người dùng này"));
        String status = terminal.callStatus() == null ? "ENDED" : terminal.callStatus().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ENDED", "DECLINED", "CANCELLED", "MISSED").contains(status)) {
            throw new ForbiddenException("Trạng thái cuộc gọi không hợp lệ để ghi lịch sử");
        }
        return persistCallHistoryMessage(callerId, receiverId, callId, terminal.callType(), status,
                status.equals("ENDED") ? Math.max(0L, terminal.durationSeconds()) : 0L);
    }

    private void validateCallHistoryParticipants(Long callerId, Long receiverId, String callId) {
        if (callerId == null || receiverId == null || callerId <= 0 || receiverId <= 0
                || callerId.equals(receiverId) || callId == null || callId.isBlank() || callId.length() > 100) {
            throw new BadRequestException("Thông tin cuộc gọi không hợp lệ");
        }
    }

    private ChatMessageResponse persistCallHistoryMessage(
            Long callerId, Long receiverId, String callId, String callType, String callStatus, long durationSeconds) {
        var existed = chatMessageRepository.findByCallId(callId);
        if (existed.isPresent()) {
            requireCallParticipants(existed.get(), callerId, receiverId);
            return toResponse(existed.get());
        }

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gọi"));
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildConversationId(String.valueOf(callerId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(callerId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent("");
        // Keep the established CALL type so the existing unique callId index remains the idempotency guard.
        message.setMessageType("CALL");
        message.setCallId(callId);
        message.setCallType("audio".equalsIgnoreCase(callType) ? "audio" : "video");
        message.setCallStatus(callStatus);
        message.setCallDurationSeconds(Math.max(0L, durationSeconds));
        message.setRead(false);

        // The database unique callId index, not the preliminary lookup, decides the concurrent winner.
        if (!documentStore.insertIfAbsent(message)) {
            ChatMessage winner = chatMessageRepository.findByCallId(callId)
                    .orElseThrow(() -> new IllegalStateException("Call history insert conflict without canonical record"));
            requireCallParticipants(winner, callerId, receiverId);
            return toResponse(winner);
        }

        ChatMessageResponse dto = toResponse(message, caller);
        afterCommitSafely("call-history message delivery", () -> {
            messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
            messagingTemplate.convertAndSendToUser(caller.getUsername(), "/queue/messages", dto);
        });
        return dto;
    }

    private void requireCallParticipants(ChatMessage message, Long caller, Long callee) {
        if (!"CALL".equals(message.getMessageType()) || !String.valueOf(caller).equals(message.getSenderId())
                || !String.valueOf(callee).equals(message.getReceiverId()))
            throw new ForbiddenException("Cuộc gọi không thuộc người tham gia này");
    }

    public long countUnreadTotal(Long userId) {
        long directUnread = conversationService.countUnreadDirect(userId);
        long groupUnread = conversationService.countUnreadGroupsByIds(
                groupService.getAllMyGroupIds(userId), userId);
        return directUnread + groupUnread;
    }

    @Transactional
    public ChatMessageResponse sendGroupMessage(
            Long senderId, Long groupId, String content, String clientId) {
        return sendGroupMessage(senderId, groupId, content, clientId, null, null, null, null);
    }

    @Transactional
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

    @Transactional
    public ChatMessageResponse sendGroupMessage(
            Long senderId,
            Long groupId,
            String content,
            String clientId,
            String mediaUrl,
            String mediaType,
            String mediaName,
            Long mediaSize) {
        String normalizedClientId = normalizeClientId(clientId);
        ChatMessageResponse previous = findExistingMessage(senderId, normalizedClientId);
        if (previous != null) return previous;
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        assertMediaAllowedForChat(mediaUrl, String.valueOf(senderId));
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
        message.setClientId(normalizedClientId);
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        attachChatMedia(saved, senderId);
        ChatMessageResponse dto = toResponse(saved, sender);
        dto.setClientId(normalizedClientId);

        String resolvedGroupName = "nhóm";
        try {
            var group = groupService.getGroupDetail(groupId, senderId);
            if (group != null && group.getName() != null && !group.getName().isBlank()) {
                resolvedGroupName = group.getName();
            }
        } catch (RuntimeException ex) {
            // The persisted message and WebSocket delivery must not depend on push metadata.
            log.warn("Unable to load group metadata for chat notification (groupId={}, cause={})",
                    groupId, ex.getClass().getSimpleName());
        }
        // Capture the resolved value as an effectively-final local for the after-commit callback.
        final String groupNameForNotification = resolvedGroupName;
        List<User> recipients = userRepository.findAllById(groupService.getMemberIds(groupId));
        afterCommitSafely("group chat delivery", () -> {
            for (User member : recipients) {
                if (member.isActive()) {
                    messagingTemplate.convertAndSendToUser(
                            member.getUsername(), "/queue/messages", dto);
                    if (!member.getId().equals(senderId)) {
                        sendChatPushSafely(
                                member.getId(),
                                "Tin nhắn trong " + groupNameForNotification,
                                displayName(sender) + ": " + notificationPreview(dto.getContent(), dto.getMediaUrl()),
                                "/chat?groupId=" + groupId);
                    }
                }
            }
        });

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
        return chatHistoryService.history(conversationId, page, currentUserId);
    }

    public ChatCursorPage getGroupMessagesByCursor(
            Long groupId, Long currentUserId, String beforeId, int requestedSize) {
        if (!groupService.isMember(groupId, currentUserId)) {
            throw new ForbiddenException("Bạn không phải thành viên của nhóm này");
        }
        return conversationHistoryByCursor(
                ChatMessage.buildGroupConversationId(groupId), currentUserId, beforeId, requestedSize);
    }

    private ChatCursorPage conversationHistoryByCursor(
            String conversationId, Long currentUserId, String beforeId, int requestedSize) {
        int size = Math.max(1, Math.min(100, requestedSize));
        java.util.List<Criteria> clauses = new java.util.ArrayList<>();
        clauses.add(Criteria.where("conversationId").is(conversationId));
        clauses.add(Criteria.where("hiddenForUserIds").nin(String.valueOf(currentUserId)));
        if (beforeId != null && !beforeId.isBlank()) {
            clauses.add(Criteria.where("_id").lt(beforeId.trim()));
        }
        Query query = Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                .with(Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<ChatMessage> messages = new java.util.ArrayList<>(documentStore.find(query, ChatMessage.class));
        boolean hasMore = messages.size() > size;
        if (hasMore) messages.remove(messages.size() - 1);
        String nextCursor = hasMore && !messages.isEmpty()
                ? messages.get(messages.size() - 1).getId()
                : null;

        java.util.Set<Long> senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .filter(java.util.Objects::nonNull)
                .map(Long::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, User> senders = userRepository.findAllById(senderIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        java.util.Collections.reverse(messages);
        List<ChatMessageResponse> items = messages.stream()
                .map(message -> toResponse(
                        message,
                        message.getSenderId() == null
                                ? null
                                : senders.get(Long.valueOf(message.getSenderId()))))
                .toList();
        return new ChatCursorPage(items, nextCursor, hasMore);
    }

    @Transactional
    public void markGroupAsRead(Long groupId, Long userId) {
        if (!groupService.isMember(groupId, userId)) {
            throw new ForbiddenException("Bạn không phải thành viên của nhóm này");
        }
        String readerId = String.valueOf(userId);
        LocalDateTime readAt = LocalDateTime.now();

        Criteria unreadForReader = new Criteria().andOperator(
                Criteria.where("groupId").is(groupId),
                Criteria.where("senderId").ne(readerId),
                Criteria.where("sentAt").lte(readAt),
                Criteria.where("readByUserIds").nin(readerId));
        Query receiptQuery = Query.query(unreadForReader)
                .with(Sort.by(Sort.Direction.DESC, "sentAt")).limit(250);
        List<ChatMessage> receiptCandidates = documentStore.find(receiptQuery, ChatMessage.class);

        // SQL lastReadAt is the authoritative source for the unread group badge. Commit it first;
        // update PostgreSQL's per-message receipts and notify senders after the SQL transaction commits.
        int updated = groupReadStatusRepository.updateLastReadAt(groupId, userId, readAt);
        if (updated == 0) {
            // Compatibility for memberships created before read-status initialization existed.
            GroupReadStatus status = new GroupReadStatus(groupId, userId);
            status.setLastReadAt(readAt);
            groupReadStatusRepository.save(status);
        }
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

        afterCommitSafely("group read receipt", () -> {
            try {
                documentStore.updateMulti(
                        Query.query(unreadForReader),
                        new Update().addToSet("readByUserIds", readerId),
                        ChatMessage.class);
            } catch (RuntimeException ex) {
                // The SQL lastReadAt keeps unread totals correct; message receipts can be repaired
                // on the next read/reconciliation rather than rolling back a committed SQL update.
                log.warn("Could not persist group read receipts for group {} ({})",
                        groupId, ex.getClass().getSimpleName());
            }
            idsBySender.forEach((senderId, messageIds) -> {
                User sender = usersById.get(Long.valueOf(senderId));
                if (sender == null || messageIds.isEmpty()) return;
                Map<String, Object> receipt = new java.util.HashMap<>();
                receipt.put("readerId", userId);
                receipt.put("groupId", groupId);
                receipt.put("readAt", readAt.toString());
                receipt.put("status", "READ");
                receipt.put("messageIds", messageIds);
                messagingTemplate.convertAndSendToUser(
                        sender.getUsername(), "/queue/read-receipts", receipt);
            });
        });
    }

    public List<ConversationResponse> getConversations(Long userId, Long ensureOtherUserId) {
        return conversationService.getConversations(userId, ensureOtherUserId);
    }

    public List<ConversationResponse> getConversations(Long userId) {
        return conversationService.getConversations(userId);
    }

    public ConversationCursorPage getConversationPage(
            Long userId, String cursor, int size) {
        return conversationService.getConversationPage(userId, cursor, size);
    }

    @Transactional
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
        String removedMediaUrl = message.getMediaUrl();
        chatMessageRepository.delete(message);
        if (mediaAssets != null && removedMediaUrl != null && !removedMediaUrl.isBlank()) {
            // Persist the deletion tombstone in the same PostgreSQL transaction as message removal.
            // Cloudinary deletion is retried by the lifecycle worker after this transaction commits.
            mediaAssets.queueUnreferencedDeletion(removedMediaUrl);
        }
        Map<String, Object> event = Map.of("messageId", messageId, "forEveryone", true);
        List<User> recipients;
        if (message.getGroupId() != null) {
            recipients = userRepository.findAllById(groupService.getMemberIds(message.getGroupId()));
        } else {
            Set<Long> ids = Set.of(Long.valueOf(message.getSenderId()), Long.valueOf(message.getReceiverId()));
            recipients = userRepository.findAllById(ids);
        }
        afterCommitSafely("message deletion broadcast", () -> {
            for (User member : recipients) {
                messagingTemplate.convertAndSendToUser(
                        member.getUsername(), "/queue/message-deletions", event);
            }
        });
    }

    private void afterCommitSafely(String operation, Runnable action) {
        TransactionCommitHooks.afterCommit(() -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                // A WebSocket/push failure must not imply the already committed message rolled back.
                log.error("Post-commit chat side effect failed (operation={}, cause={})",
                        operation, ex.getClass().getSimpleName(), ex);
            }
        });
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

    private void attachChatMedia(ChatMessage message, Long uploaderId) {
        if (mediaAssets == null || message == null || message.getMediaUrl() == null
                || message.getMediaUrl().isBlank() || message.getId() == null || uploaderId == null) {
            return;
        }
        mediaAssets.attachManagedReference(
                message.getMediaUrl(), UploadPurpose.CHAT, String.valueOf(uploaderId),
                "CHAT_MESSAGE", message.getId());
    }

    private void assertMediaAllowedForChat(String mediaUrl, String senderId) {
        if (mediaUrl == null || mediaUrl.isBlank()) return;
        if (mediaUploadModeration == null) {
            throw new BadRequestException("Không thể xác minh media chat; vui lòng tải lên lại");
        }
        mediaUploadModeration.requirePurposeUrl(mediaUrl, UploadPurpose.CHAT, senderId, "image", "video", "raw");
    }

    private String notificationPreview(String content, String mediaUrl) {
        String preview = content == null ? "" : content.trim();
        if (preview.isBlank() && mediaUrl != null && !mediaUrl.isBlank()) preview = "Đã gửi tệp đính kèm";
        if (preview.isBlank()) preview = "Bạn có tin nhắn mới";
        return preview.length() > 140 ? preview.substring(0, 137) + "…" : preview;
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return chatMessageMapper.toResponse(message);
    }

    private ChatMessageResponse toResponse(ChatMessage message, User sender) {
        return chatMessageMapper.toResponse(message, sender);
    }

}
