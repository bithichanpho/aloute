package com.aloute.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.ChatMessage;

public interface IChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderBySentAtAsc(String conversationId);

    List<ChatMessage> findBySenderIdOrReceiverIdOrderBySentAtDesc(String senderId, String receiverId);

    List<ChatMessage> findByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);

    long countByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId);

    long countByReceiverIdAndReadFalse(String receiverId);
}