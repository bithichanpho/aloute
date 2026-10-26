package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ChatMessageMapper {
    private final IUserRepository userRepository;

    public ChatMessageMapper(IUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ChatMessageResponse toResponse(ChatMessage m) {
        User sender =
                m.getSenderId() == null
                        ? null
                        : userRepository.findById(Long.valueOf(m.getSenderId())).orElse(null);
        return toResponse(m, sender);
    }

    public ChatMessageResponse toResponse(ChatMessage m, User sender) {
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
        dto.setDelivered(m.isDelivered() || (m.getGroupId() != null && !m.getDeliveredToUserIds().isEmpty()));
        dto.setDeliveredAt(m.getDeliveredAt());
        dto.setDeliveredCount(m.getGroupId() != null ? m.getDeliveredToUserIds().size() : (m.isDelivered() ? 1 : 0));
        dto.setReadCount(m.getGroupId() != null ? m.getReadByUserIds().size() : (m.isRead() ? 1 : 0));
        dto.setSentAt(m.getSentAt() != null ? m.getSentAt() : LocalDateTime.now());
        dto.setClientId(m.getClientId());
        dto.setTimeAgo(TimeAgoUtil.format(m.getSentAt()));

        if (sender != null) {
            dto.setSenderUsername(sender.getUsername());
            dto.setSenderFullName(sender.getFullName());
            dto.setSenderAvatarUrl(AvatarUtil.resolve(sender.getAvatarUrl()));
        }
        return dto;
    }
}
