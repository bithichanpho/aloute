package com.aloute.service;

import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Paginated conversation history with hidden-message filtering and sender enrichment. */
@Service
public class ChatHistoryService {
    private final IChatMessageRepository chatMessageRepository;
    private final IUserRepository userRepository;
    private final ChatMessageMapper chatMessageMapper;

    public ChatHistoryService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            ChatMessageMapper chatMessageMapper) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.chatMessageMapper = chatMessageMapper;
    }

    public List<ChatMessageResponse> history(String conversationId, int page, Long currentUserId) {
        int safePage = Math.max(0, page);
        var messages = new ArrayList<>(chatMessageRepository.findByConversationIdOrderBySentAtDesc(
                conversationId,
                PageRequest.of(safePage, 50, Sort.by(Sort.Direction.DESC, "sentAt", "id"))));
        Collections.reverse(messages);
        messages.removeIf(message -> message.getHiddenForUserIds().contains(String.valueOf(currentUserId)));

        Set<Long> senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .filter(java.util.Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        var users = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));

        return messages.stream()
                .map(message -> chatMessageMapper.toResponse(
                        message,
                        message.getSenderId() != null ? users.get(Long.valueOf(message.getSenderId())) : null))
                .toList();
    }
}
