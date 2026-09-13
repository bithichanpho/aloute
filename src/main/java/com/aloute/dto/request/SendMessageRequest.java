package com.aloute.dto.request;

public class SendMessageRequest {

    private Long receiverId; // dùng cho chat 1-1, để trống nếu là tin nhắn nhóm
    private Long groupId;    // dùng cho chat nhóm, để trống nếu là chat 1-1
    private String content;
    private String clientId; // id tạm do client sinh ra, dùng để đối chiếu lại bubble "đang gửi" khi server echo về

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

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}