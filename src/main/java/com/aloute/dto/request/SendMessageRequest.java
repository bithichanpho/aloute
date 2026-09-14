package com.aloute.dto.request;

public class SendMessageRequest {

    private Long receiverId; // dùng cho chat 1-1, để trống nếu là tin nhắn nhóm
    private Long groupId;    // dùng cho chat nhóm, để trống nếu là chat 1-1
    private String content;
    private String clientId;
    private String mediaUrl;
    private String mediaType;
    private String mediaName;
    private Long mediaSize;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
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


    public String getMediaName() { return mediaName; }
    public void setMediaName(String mediaName) { this.mediaName = mediaName; }
    public Long getMediaSize() { return mediaSize; }
    public void setMediaSize(Long mediaSize) { this.mediaSize = mediaSize; }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}