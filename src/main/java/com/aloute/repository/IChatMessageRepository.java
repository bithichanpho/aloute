package com.aloute.repository;

import com.aloute.model.document.ChatMessage;
import com.aloute.repository.document.DocumentCrudRepository;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IChatMessageRepository extends DocumentCrudRepository<ChatMessage, String> {
    List<ChatMessage> findByConversationIdOrderBySentAtDesc(String conversationId, Pageable pageable);
    List<ChatMessage> findBySenderIdOrReceiverIdOrderBySentAtDesc(String senderId, String receiverId);
    List<ChatMessage> findByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);
    long countByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);
    long countByReceiverIdAndReadFalse(String receiverId);
    Optional<ChatMessage> findFirstByConversationIdOrderBySentAtDesc(String conversationId);
    long countByConversationIdAndSentAtAfter(String conversationId, LocalDateTime after);
    long countByConversationId(String conversationId);
    Optional<ChatMessage> findBySenderIdAndClientId(String senderId, String clientId);
    boolean existsBySenderIdAndClientId(String senderId, String clientId);
    Optional<ChatMessage> findByCallId(String callId);
}
