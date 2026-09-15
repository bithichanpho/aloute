package com.aloute.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.ChatMessage;

public interface IChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderBySentAtDesc(String conversationId, org.springframework.data.domain.Pageable pageable);

    List<ChatMessage> findBySenderIdOrReceiverIdOrderBySentAtDesc(String senderId, String receiverId);

    List<ChatMessage> findByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);

    long countByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);

    long countByReceiverIdAndReadFalse(String receiverId);

    // ---- Dành cho nhóm: conversationId dạng "group_<id>" ----
    java.util.Optional<ChatMessage> findFirstByConversationIdOrderBySentAtDesc(String conversationId);

    long countByConversationIdAndSentAtAfter(String conversationId, java.time.LocalDateTime after);

    long countByConversationId(String conversationId);

    java.util.Optional<ChatMessage> findByCallId(String callId);
}