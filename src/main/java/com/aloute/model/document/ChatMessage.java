package com.aloute.model.document;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "chat_messages")
public class ChatMessage {

    @Id
    private String id;

    @Indexed
    private String conversationId; // ghép 2 userId theo thứ tự cố định, vd "3_10"

    private String senderId;
    private String receiverId; // null nếu đây là tin nhắn nhóm
    private Long groupId;      // null nếu đây là tin nhắn 1-1
    private String content;
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