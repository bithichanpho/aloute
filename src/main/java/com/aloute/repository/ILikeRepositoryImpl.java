package com.aloute.repository;

import com.aloute.model.document.Like;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.repository.document.PostgresDocumentRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class ILikeRepositoryImpl extends PostgresDocumentRepository<Like> implements ILikeRepository {
    public ILikeRepositoryImpl(PostgresDocumentStore store) { super(store, Like.class); }
    @Override public Optional<Like> findByPostIdAndUserId(String postId, String userId) {
        return store.findOne(Query.query(new Criteria().andOperator(Criteria.where("postId").is(postId), Criteria.where("userId").is(userId))), Like.class);
    }
    @Override public boolean existsByPostIdAndUserId(String postId, String userId) {
        return store.exists(Query.query(new Criteria().andOperator(Criteria.where("postId").is(postId), Criteria.where("userId").is(userId))), Like.class);
    }
    @Override public long countByPostId(String postId) { return store.count(Query.query(Criteria.where("postId").is(postId)), Like.class); }
    @Override public List<Like> findByPostIdInAndUserId(List<String> postIds, String userId) {
        if (postIds == null || postIds.isEmpty()) return List.of();
        return store.find(Query.query(new Criteria().andOperator(Criteria.where("postId").in(postIds), Criteria.where("userId").is(userId))), Like.class);
    }
    @Override public long deleteByPostId(String postId) { return store.remove(Query.query(Criteria.where("postId").is(postId)), Like.class).getDeletedCount(); }
}
