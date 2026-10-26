package com.aloute.repository;

import com.aloute.model.document.ChatMessage;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.repository.document.PostgresDocumentRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class IChatMessageRepositoryImpl extends PostgresDocumentRepository<ChatMessage> implements IChatMessageRepository {
    public IChatMessageRepositoryImpl(PostgresDocumentStore store) { super(store, ChatMessage.class); }
    @Override public List<ChatMessage> findByConversationIdOrderBySentAtDesc(String conversationId, Pageable pageable) {
        Query query = Query.query(Criteria.where("conversationId").is(conversationId)).with(Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("_id")));
        page(query, pageable); return store.find(query, ChatMessage.class);
    }
    @Override public List<ChatMessage> findBySenderIdOrReceiverIdOrderBySentAtDesc(String senderId, String receiverId) {
        Query query = Query.query(new Criteria().orOperator(Criteria.where("senderId").is(senderId), Criteria.where("receiverId").is(receiverId)))
                .with(Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("_id")));
        return store.find(query, ChatMessage.class);
    }
    @Override public List<ChatMessage> findByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId) {
        return store.find(Query.query(new Criteria().andOperator(Criteria.where("receiverId").is(receiverId), Criteria.where("senderId").is(senderId), Criteria.where("read").is(false))), ChatMessage.class);
    }
    @Override public long countByReceiverIdAndSenderIdAndReadFalse(String receiverId, String senderId) {
        return store.count(Query.query(new Criteria().andOperator(Criteria.where("receiverId").is(receiverId), Criteria.where("senderId").is(senderId), Criteria.where("read").is(false))), ChatMessage.class);
    }
    @Override public long countByReceiverIdAndReadFalse(String receiverId) {
        return store.count(Query.query(new Criteria().andOperator(Criteria.where("receiverId").is(receiverId), Criteria.where("read").is(false))), ChatMessage.class);
    }
    @Override public Optional<ChatMessage> findFirstByConversationIdOrderBySentAtDesc(String conversationId) {
        return store.findOne(Query.query(Criteria.where("conversationId").is(conversationId)).with(Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("_id"))), ChatMessage.class);
    }
    @Override public long countByConversationIdAndSentAtAfter(String conversationId, LocalDateTime after) {
        return store.count(Query.query(new Criteria().andOperator(Criteria.where("conversationId").is(conversationId), Criteria.where("sentAt").gt(after))), ChatMessage.class);
    }
    @Override public long countByConversationId(String conversationId) { return store.count(Query.query(Criteria.where("conversationId").is(conversationId)), ChatMessage.class); }
    @Override public Optional<ChatMessage> findBySenderIdAndClientId(String senderId, String clientId) {
        return store.findOne(Query.query(new Criteria().andOperator(Criteria.where("senderId").is(senderId), Criteria.where("clientId").is(clientId))), ChatMessage.class);
    }
    @Override public boolean existsBySenderIdAndClientId(String senderId, String clientId) {
        return store.exists(Query.query(new Criteria().andOperator(Criteria.where("senderId").is(senderId), Criteria.where("clientId").is(clientId))), ChatMessage.class);
    }
    @Override public Optional<ChatMessage> findByCallId(String callId) { return store.findOne(Query.query(Criteria.where("callId").is(callId)), ChatMessage.class); }
    private void page(Query query, Pageable pageable) { if (pageable != null && pageable.isPaged()) query.skip(pageable.getOffset()).limit(pageable.getPageSize()); if (pageable != null && pageable.getSort().isSorted()) query.with(pageable.getSort()); }
}
