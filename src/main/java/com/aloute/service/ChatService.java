package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.Document;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

@Service
public class ChatService {

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
            MongoTemplate mongoTemplate) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.groupService = groupService;
        this.messagingTemplate = messagingTemplate;
        this.friendService = friendService;
        this.presenceService = presenceService;
        this.mongoTemplate = mongoTemplate;
    }

    // ================= CHAT 1-1 =================

    /**
     * Lưu tin nhắn vào MongoDB rồi đẩy real-time tới người nhận (nếu đang mở app)
     * qua /user/{username}/queue/messages, đồng thời đẩy lại cho chính người gửi
     * để đồng bộ nếu họ đang mở nhiều tab/thiết bị.
     *
     * Quyền nhắn tin: mở cho tất cả mọi người. Riêng tài khoản bật chế độ riêng tư
     * (users.is_private = true) thì chỉ bạn bè (theo dõi lẫn nhau) mới nhắn được.
     */
    public ChatMessageResponse sendMessage(Long senderId, Long receiverId, String content, String clientId) {
        return sendMessage(senderId, receiverId, content, clientId, null, null, null, null);
    }

    public ChatMessageResponse sendMessage(Long senderId, Long receiverId, String content, String clientId, String mediaUrl, String mediaType, String mediaName, Long mediaSize) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (senderId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự nhắn tin cho chính mình");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gửi"));
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        // Mặc định ai cũng nhắn tin được. Chỉ khi người nhận bật "tài khoản riêng tư"
        // thì mới bắt buộc hai bên phải theo dõi lẫn nhau (bạn bè).
        if (receiver.isPrivateAccount() && !friendService.isFriend(senderId, receiverId)) {
            throw new BadRequestException("Tài khoản này ở chế độ riêng tư — hai bạn cần theo dõi lẫn nhau mới nhắn tin được");
        }

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildConversationId(String.valueOf(senderId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(senderId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved);
        dto.setClientId(clientId);

        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
        messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", dto);

        return dto;
    }

    /** Lịch sử tin nhắn giữa 2 người, cũ -> mới; mặc định lấy 50 tin gần nhất. */
    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId) {
        return getMessages(currentUserId, otherUserId, 0);
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId, int page) {
        String conversationId = ChatMessage.buildConversationId(String.valueOf(currentUserId), String.valueOf(otherUserId));
        return history(conversationId, page);
    }

    /**
     * Đánh dấu toàn bộ tin nhắn CỦA otherUserId gửi cho currentUserId là đã đọc,
     * đồng thời báo real-time ngược lại cho otherUserId (nếu đang mở app) để họ
     * thấy bubble mình vừa gửi chuyển thành "Đã xem" ngay lập tức, không cần tải lại.
     */
    public void markAsRead(Long currentUserId, Long otherUserId) {
        // MỘT lệnh update duy nhất dưới Mongo. Bản cũ tải toàn bộ tin chưa đọc lên
        // JVM rồi saveAll() — mở chat có 500 tin chưa đọc là 500 document đi về
        // qua mạng chỉ để lật một cờ boolean.
        long updated = mongoTemplate.updateMulti(
                Query.query(Criteria.where("receiverId").is(String.valueOf(currentUserId))
                        .and("senderId").is(String.valueOf(otherUserId))
                        .and("read").is(false)
                        .and("groupId").is(null)),
                new Update().set("read", true), ChatMessage.class).getModifiedCount();
        if (updated == 0) {
            return;
        }

        userRepository.findById(otherUserId).ifPresent(otherUser -> messagingTemplate.convertAndSendToUser(
                otherUser.getUsername(), "/queue/read-receipts",
                Map.of("readerId", currentUserId, "readAt", LocalDateTime.now().toString())));
    }

    /**
     * Tạo một "tin nhắn hệ thống" đại diện cho cuộc gọi nhỡ.
     * Nó được lưu cùng collection chat_messages để xuất hiện trong lịch sử chat
     * và tự động trở thành mục mới nhất của hội thoại.
     */
    public ChatMessageResponse createMissedCallMessage(Long callerId, Long receiverId, String callId, String callType) {
        if (callerId == null || receiverId == null || callId == null || callId.isBlank()) {
            return null;
        }

        // Idempotent: timeout/retry không tạo 2 dòng cuộc gọi nhỡ.
        var existed = chatMessageRepository.findByCallId(callId);
        if (existed.isPresent()) {
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
        message.setMessageType("CALL");
        message.setCallId(callId);
        message.setCallType("audio".equalsIgnoreCase(callType) ? "audio" : "video");
        message.setCallStatus("MISSED");
        // Người nhận chưa xem dòng cuộc gọi nhỡ thì badge/unread vẫn hoạt động như tin nhắn.
        message.setRead(false);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved);

        // Cả hai phía thấy cùng một dòng trong lịch sử; phía nhận sẽ có unread nếu chat chưa mở.
        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
        messagingTemplate.convertAndSendToUser(caller.getUsername(), "/queue/messages", dto);
        return dto;
    }

    public long countUnreadTotal(Long userId) {
        long directUnread = chatMessageRepository.countByReceiverIdAndReadFalse(String.valueOf(userId));
        long groupUnread = groupUnreadCounts(groupService.getMyGroups(userId), userId).values().stream()
                .mapToLong(Long::longValue).sum();
        return directUnread + groupUnread;
    }

    // ================= CHAT NHÓM =================

    /** Gửi tin nhắn nhóm — chỉ thành viên nhóm mới gửi được, đẩy real-time tới TẤT CẢ thành viên. */
    public ChatMessageResponse sendGroupMessage(Long senderId, Long groupId, String content, String clientId) {
        return sendGroupMessage(senderId, groupId, content, clientId, null, null, null, null);
    }

    public ChatMessageResponse sendGroupMessage(Long senderId, Long groupId, String content, String clientId, String mediaUrl, String mediaType) {
        return sendGroupMessage(senderId, groupId, content, clientId, mediaUrl, mediaType, null, null);
    }

    public ChatMessageResponse sendGroupMessage(Long senderId, Long groupId, String content, String clientId, String mediaUrl, String mediaType, String mediaName, Long mediaSize) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (!groupService.isMember(groupId, senderId)) {
            throw new UnauthorizedException("Bạn không phải thành viên của nhóm này");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gửi"));

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
        ChatMessageResponse dto = toResponse(saved);
        dto.setClientId(clientId);

        // Đẩy tới TẤT CẢ thành viên nhóm (kể cả người gửi, để đồng bộ nhiều tab/thiết bị)
        for (Long memberId : groupService.getMemberIds(groupId)) {
            userRepository.findById(memberId).ifPresent(
                    u -> messagingTemplate.convertAndSendToUser(u.getUsername(), "/queue/messages", dto));
        }

        return dto;
    }

    /** Lịch sử tin nhắn trong nhóm, cũ -> mới; mặc định lấy 50 tin gần nhất. */
    public List<ChatMessageResponse> getGroupMessages(Long groupId, Long currentUserId) {
        return getGroupMessages(groupId, currentUserId, 0);
    }

    public List<ChatMessageResponse> getGroupMessages(Long groupId, Long currentUserId, int page) {
        if (!groupService.isMember(groupId, currentUserId)) {
            throw new UnauthorizedException("Bạn không phải thành viên của nhóm này");
        }
        String conversationId = ChatMessage.buildGroupConversationId(groupId);
        return history(conversationId, page);
    }

    /** Cập nhật mốc "đã đọc đến lúc này" của user trong nhóm — dùng để tính lại unreadCount. */
    public void markGroupAsRead(Long groupId, Long userId) {
        GroupReadStatus status = groupReadStatusRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseGet(() -> new GroupReadStatus(groupId, userId));
        status.setLastReadAt(LocalDateTime.now());
        groupReadStatusRepository.save(status);
    }

    /**
     * Đếm tin chưa đọc cho TẤT CẢ nhóm của một người bằng đúng 2 truy vấn
     * (1 SQL lấy mốc đã đọc + 1 aggregation Mongo), thay cho 2 query mỗi nhóm.
     */
    private Map<String, Long> groupUnreadCounts(List<ChatGroup> groups, Long userId) {
        if (groups.isEmpty()) {
            return Map.of();
        }

        Map<Long, LocalDateTime> readTimes = groupReadStatusRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(GroupReadStatus::getGroupId, GroupReadStatus::getLastReadAt));

        Criteria[] filters = groups.stream().map(g -> {
            Criteria c = Criteria.where("conversationId").is(ChatMessage.buildGroupConversationId(g.getId()));
            if (readTimes.containsKey(g.getId())) {
                c = c.and("sentAt").gt(readTimes.get(g.getId()));
            }
            return c;
        }).toArray(Criteria[]::new);

        Map<String, Long> counts = new java.util.HashMap<>();
        mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(new Criteria().orOperator(filters)),
                        Aggregation.group("conversationId").count().as("count")),
                ChatMessage.class, Document.class)
                .forEach(d -> counts.put(d.getString("_id"), ((Number) d.get("count")).longValue()));
        return counts;
    }

    private long countGroupUnread(Long groupId, Long userId) {
        String conversationId = ChatMessage.buildGroupConversationId(groupId);
        return groupReadStatusRepository.findByGroupIdAndUserId(groupId, userId)
                .map(status -> chatMessageRepository.countByConversationIdAndSentAtAfter(conversationId, status.getLastReadAt()))
                .orElseGet(() -> chatMessageRepository.countByConversationId(conversationId));
    }

    // ================= DANH SÁCH HỘI THOẠI HỢP NHẤT (1-1 + nhóm) =================

    /**
     * Như {@link #getConversations(Long)} nhưng đảm bảo luôn có sẵn ô hội thoại với
     * {@code ensureOtherUserId} — dùng khi người dùng bấm "Nhắn tin" từ trang cá nhân
     * của một người chưa từng nhắn và cũng chưa phải bạn bè.
     */
    public List<ConversationResponse> getConversations(Long userId, Long ensureOtherUserId) {
        List<ConversationResponse> conversations = getConversations(userId);
        if (ensureOtherUserId == null || ensureOtherUserId.equals(userId)) {
            return conversations;
        }
        boolean already = conversations.stream()
                .anyMatch(c -> !c.isGroup() && ensureOtherUserId.equals(c.getOtherUserId()));
        if (!already && userRepository.existsById(ensureOtherUserId)) {
            conversations.add(0, toFriendPreviewResponse(ensureOtherUserId));
            fillOtherUserInfo(conversations);
        }
        return conversations;
    }

    /**
     * Gộp chung hội thoại 1-1 và nhóm vào 1 danh sách duy nhất cho cột trái
     * trang /chat, sắp theo thời điểm tin nhắn mới nhất trước.
     */
    public List<ConversationResponse> getConversations(Long userId) {
        String userIdStr = String.valueOf(userId);

        // Lấy TIN MỚI NHẤT của mỗi hội thoại 1-1 ngay trong Mongo.
        // Bản cũ kéo TOÀN BỘ tin nhắn của người dùng về JVM rồi mới lọc ra tin cuối
        // của từng hội thoại — tài khoản nhắn nhiều là mỗi lần mở /chat phải tải
        // hàng chục nghìn document chỉ để hiển thị vài dòng preview.
        List<ChatMessage> latestDirect = mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(new Criteria().andOperator(
                                Criteria.where("groupId").is(null),
                                new Criteria().orOperator(
                                        Criteria.where("senderId").is(userIdStr),
                                        Criteria.where("receiverId").is(userIdStr)))),
                        Aggregation.sort(Sort.Direction.DESC, "sentAt", "_id"),
                        Aggregation.group("conversationId").first(Aggregation.ROOT).as("latest"),
                        Aggregation.replaceRoot("latest"),
                        Aggregation.sort(Sort.Direction.DESC, "sentAt", "_id"),
                        Aggregation.limit(100)),
                ChatMessage.class, ChatMessage.class).getMappedResults();

        // Số tin chưa đọc theo từng người gửi — 1 aggregation thay cho 1 query mỗi ô hội thoại.
        Map<String, Long> unreadBySender = new java.util.HashMap<>();
        mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("receiverId").is(userIdStr)
                                .and("read").is(false).and("groupId").is(null)),
                        Aggregation.group("senderId").count().as("count")),
                ChatMessage.class, Document.class)
                .forEach(d -> unreadBySender.put(d.getString("_id"), ((Number) d.get("count")).longValue()));

        List<ConversationResponse> result = latestDirect.stream()
                .map(latest -> toDirectConversationResponse(userIdStr, latest, unreadBySender))
                .collect(Collectors.toList());

        // Bạn bè (đã follow lẫn nhau) nhưng CHƯA từng nhắn tin — vẫn hiện sẵn trong
        // danh sách để bắt đầu trò chuyện ngay, đúng yêu cầu "follow nhau xong thành bạn bè".
        Set<Long> otherIdsWithConversation = result.stream()
                .map(ConversationResponse::getOtherUserId)
                .collect(Collectors.toSet());

        for (Long friendId : friendService.getMutualFriendIds(userId)) {
            if (!otherIdsWithConversation.contains(friendId)) {
                result.add(toFriendPreviewResponse(friendId));
            }
        }

        // Nhóm: gộp tin mới nhất + số thành viên + số chưa đọc thành vài truy vấn,
        // thay vì 3 truy vấn nhân với số nhóm.
        List<ChatGroup> groups = groupService.getMyGroups(userId);
        Map<String, Long> groupUnread = groupUnreadCounts(groups, userId);
        Map<Long, Integer> memberCounts = groupService.getMemberCounts(
                groups.stream().map(ChatGroup::getId).collect(Collectors.toList()));

        Map<String, ChatMessage> latestGroupMessages = new java.util.HashMap<>();
        if (!groups.isEmpty()) {
            mongoTemplate.aggregate(Aggregation.newAggregation(
                            Aggregation.match(Criteria.where("conversationId").in(groups.stream()
                                    .map(g -> ChatMessage.buildGroupConversationId(g.getId()))
                                    .collect(Collectors.toList()))),
                            Aggregation.sort(Sort.Direction.DESC, "sentAt", "_id"),
                            Aggregation.group("conversationId").first(Aggregation.ROOT).as("latest"),
                            Aggregation.replaceRoot("latest")),
                    ChatMessage.class, ChatMessage.class)
                    .forEach(m -> latestGroupMessages.put(m.getConversationId(), m));
        }

        for (ChatGroup group : groups) {
            String conversationId = ChatMessage.buildGroupConversationId(group.getId());
            result.add(toGroupConversationResponse(group, latestGroupMessages.get(conversationId),
                    memberCounts.getOrDefault(group.getId(), 0),
                    groupUnread.getOrDefault(conversationId, 0L)));
        }

        // Gop tra cuu user thanh 1 query, phai chay TRUOC sort vi sort dung otherFullName.
        fillOtherUserInfo(result);

        result.sort((a, b) -> {
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

    private ConversationResponse toDirectConversationResponse(String currentUserIdStr, ChatMessage latest,
            Map<String, Long> unreadBySender) {
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
        // Ten/avatar/online duoc do vao sau bang MOT query gop (xem fillOtherUserInfo).
        return dto;
    }

    /** Bạn bè chưa từng nhắn tin — hiện trong danh sách với "lastMessage" rỗng, mời bắt đầu trò chuyện. */
    private ConversationResponse toFriendPreviewResponse(Long friendId) {
        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(false);
        dto.setOtherUserId(friendId);
        dto.setHasConversation(false);
        dto.setUnreadCount(0);
        // online duoc do vao sau bang MOT query gop (xem fillOtherUserInfo).
        return dto;
    }

    /**
     * Đổ tên/avatar/trạng thái online cho toàn bộ hội thoại 1-1 bằng ĐÚNG MỘT query tới bảng users.
     *
     * Trước đây mỗi ô hội thoại tự gọi userRepository.findById() — danh sách 40 người
     * là 40 query chỉ để lấy cái tên, đây là nguyên nhân chính khiến trang /chat ì.
     *
     * Trạng thái online cũng được gộp vào đây thay vì gọi presenceService.isOnline(Long)
     * riêng cho từng dòng: hàm đó nhận userId nhưng PresenceService chỉ lưu theo username
     * (một Map trong RAM), nên bản thân nó lại phải tự query DB để đổi id -> username —
     * tức là mỗi dòng hội thoại âm thầm cõng thêm 1 query chỉ để tra một Map trong bộ nhớ.
     * Ở đây username đã có sẵn từ query gộp phía trên nên tra online hoàn toàn miễn phí.
     */
    private void fillOtherUserInfo(List<ConversationResponse> conversations) {
        Set<Long> ids = conversations.stream()
                .filter(c -> !c.isGroup() && c.getOtherUserId() != null && c.getOtherUsername() == null)
                .map(ConversationResponse::getOtherUserId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return;

        Map<Long, User> byId = userRepository.findAllById(ids).stream()
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

    private ConversationResponse toGroupConversationResponse(ChatGroup group, ChatMessage latest,
            int memberCount, long unreadCount) {

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
            String call = "audio".equalsIgnoreCase(message.getCallType()) ? "Cuộc gọi thoại" : "Cuộc gọi video";
            if ("MISSED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " nhỡ";
            if ("REJECTED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " bị từ chối";
            return "☎ " + call;
        }
        if (message.getMediaType() != null) {
            if ("video".equalsIgnoreCase(message.getMediaType())) return "🎥 Video";
            if ("file".equalsIgnoreCase(message.getMediaType())) return "📎 " + (message.getMediaName() != null ? message.getMediaName() : "Tệp đính kèm");
            return "📷 Ảnh";
        }
        return message.getContent() != null ? message.getContent() : "";
    }

    private List<ChatMessageResponse> history(String conversationId, int page) {
        int safePage = Math.max(0, page);
        var messages = new java.util.ArrayList<>(chatMessageRepository.findByConversationIdOrderBySentAtDesc(
                conversationId, PageRequest.of(safePage, 50, Sort.by(Sort.Direction.DESC, "sentAt", "id"))));
        java.util.Collections.reverse(messages);

        var senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .filter(java.util.Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        var users = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));

        return messages.stream()
                .map(m -> toResponse(m, users.get(m.getSenderId() != null ? Long.valueOf(m.getSenderId()) : null)))
                .collect(Collectors.toList());
    }

    private ChatMessageResponse toResponse(ChatMessage m) {
        User sender = m.getSenderId() == null ? null
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