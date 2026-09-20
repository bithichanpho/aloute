package com.aloute.model.document;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "chat_messages")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "conversation_sent_index", def = "{'conversationId': 1, 'sentAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "chat_unread_sender", def = "{'receiverId': 1, 'read': 1, 'senderId': 1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "chat_sender_sent", def = "{'senderId': 1, 'sentAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "chat_receiver_sent", def = "{'receiverId': 1, 'sentAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "chat_group_sent", def = "{'groupId': 1, 'sentAt': -1}")
})
public class ChatMessage {

    @Id
    private String id;

    @Indexed
    private String conversationId; // ghép 2 userId theo thứ tự cố định, vd "3_10"

    private String senderId;
    private String receiverId; // null nếu đây là tin nhắn nhóm
    private Long groupId;      // null nếu đây là tin nhắn 1-1
    private String content;
    private String mediaUrl;
    private String mediaType; // image | video | file
    private String mediaName;
    private Long mediaSize;

    // Tin nhắn đặc biệt cho cuộc gọi: CALL / MISSED / ANSWERED / REJECTED
    private String messageType; // TEXT | CALL
    private String callId;
    private String callType; // audio | video
    private String callStatus; // MISSED | REJECTED | ANSWERED

    private boolean read = false;
    private LocalDateTime sentAt;

    public ChatMessage() {
        this.sentAt = LocalDateTime.now();
    }

    /** Sinh conversationId cho tin nhắn NHÓM, tách biệt khỏi id user để không đụng nhau. */
    public static String buildGroupConversationId(Long groupId) {
        return "group_" + groupId;
    }

    /** Sinh conversationId ổn định giữa 2 user, không phụ thuộc thứ tự truyền vào. */
    public static String buildConversationId(String userId1, String userId2) {
        long a = Long.parseLong(userId1);
        long b = Long.parseLong(userId2);
        return a < b ? a + "_" + b : b + "_" + a;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }


    public String getMediaName() {
        return mediaName;
    }

    public void setMediaName(String mediaName) {
        this.mediaName = mediaName;
    }

    public Long getMediaSize() {
        return mediaSize;
    }

    public void setMediaSize(Long mediaSize) {
        this.mediaSize = mediaSize;
    }


    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public String getCallType() { return callType; }
    public void setCallType(String callType) { this.callType = callType; }

    public String getCallStatus() { return callStatus; }
    public void setCallStatus(String callStatus) { this.callStatus = callStatus; }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}