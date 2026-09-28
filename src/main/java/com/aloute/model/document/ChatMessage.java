package com.aloute.model.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Document(collection = "chat_messages")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "conversation_sent_index",
            def = "{'conversationId': 1, 'sentAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "chat_unread_sender",
            def = "{'receiverId': 1, 'read': 1, 'senderId': 1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "chat_sender_sent",
            def = "{'senderId': 1, 'sentAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "chat_receiver_sent",
            def = "{'receiverId': 1, 'sentAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "chat_group_sent",
            def = "{'groupId': 1, 'sentAt': -1}")
})
public class ChatMessage {

    @Id private String id;

    @Indexed private String conversationId;

    private String senderId;
    private String receiverId;
    private Long groupId;
    private String content;
    private String mediaUrl;
    private String mediaType;
    private String mediaName;
    private Long mediaSize;

    private String messageType;
    private String callId;
    private String callType;
    private String callStatus;
    private String storyId;
    private String storyContent;
    private String storyImageUrl;
    private String storyVideoUrl;

    private boolean read = false;
    private LocalDateTime sentAt;
    private Set<String> hiddenForUserIds = new HashSet<>();

    public ChatMessage() {
        this.sentAt = LocalDateTime.now();
    }

    public static String buildGroupConversationId(Long groupId) {
        return "group_" + groupId;
    }

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

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getCallType() {
        return callType;
    }

    public void setCallType(String callType) {
        this.callType = callType;
    }

    public String getCallStatus() {
        return callStatus;
    }

    public void setCallStatus(String callStatus) {
        this.callStatus = callStatus;
    }

    public String getStoryId() { return storyId; }
    public void setStoryId(String storyId) { this.storyId = storyId; }
    public String getStoryContent() { return storyContent; }
    public void setStoryContent(String storyContent) { this.storyContent = storyContent; }
    public String getStoryImageUrl() { return storyImageUrl; }
    public void setStoryImageUrl(String storyImageUrl) { this.storyImageUrl = storyImageUrl; }
    public String getStoryVideoUrl() { return storyVideoUrl; }
    public void setStoryVideoUrl(String storyVideoUrl) { this.storyVideoUrl = storyVideoUrl; }

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

    public Set<String> getHiddenForUserIds() {
        if (hiddenForUserIds == null) hiddenForUserIds = new HashSet<>();
        return hiddenForUserIds;
    }

    public void setHiddenForUserIds(Set<String> hiddenForUserIds) {
        this.hiddenForUserIds = hiddenForUserIds == null ? new HashSet<>() : hiddenForUserIds;
    }
}
