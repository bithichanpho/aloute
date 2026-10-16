package com.aloute.dto.request;

import com.aloute.validation.HttpUrl;
import com.aloute.validation.ValidChatMessage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@ValidChatMessage
public class SendMessageRequest {

    @Positive(message = "Người nhận không hợp lệ")
    private Long receiverId;
    @Positive(message = "Nhóm nhận không hợp lệ")
    private Long groupId;
    @Size(max = 1000, message = "Tin nhắn tối đa 1000 ký tự")
    private String content;
    @Size(max = 100, message = "Mã tin nhắn tối đa 100 ký tự")
    private String clientId;
    @Size(max = 2048, message = "URL tệp tối đa 2048 ký tự")
    @HttpUrl
    private String mediaUrl;
    @Pattern(
            regexp = "(?i)image|video|audio|file",
            message = "Loại tệp đính kèm không hợp lệ")
    private String mediaType;
    @Size(max = 255, message = "Tên tệp tối đa 255 ký tự")
    private String mediaName;
    @Positive(message = "Dung lượng tệp phải lớn hơn 0")
    @Max(value = 104857600, message = "Tệp không được vượt quá 100MB")
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

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
