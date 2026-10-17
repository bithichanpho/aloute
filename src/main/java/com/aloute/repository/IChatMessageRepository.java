package com.aloute.repository;

import com.aloute.model.document.ChatMessage;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderBySentAtDesc(
            String conversationId, org.springframework.data.domain.Pageable pageable);

    List<ChatMessage> findBySenderIdOrReceiverIdOrderBySentAtDesc(
            String senderId, String receiverId);

    List<ChatMessage> findByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);

    long countByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);

    long countByReceiverIdAndReadFalse(String receiverId);

    java.util.Optional<ChatMessage> findFirstByConversationIdOrderBySentAtDesc(
            String conversationId);

    long countByConversationIdAndSentAtAfter(String conversationId, java.time.LocalDateTime after);

    long countByConversationId(String conversationId);

    java.util.Optional<ChatMessage> findBySenderIdAndClientId(String senderId, String clientId);

    boolean existsBySenderIdAndClientId(String senderId, String clientId);

    java.util.Optional<ChatMessage> findByCallId(String callId);
}
