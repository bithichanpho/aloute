package com.aloute.repository;

import com.aloute.model.document.Repost;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.repository.document.PostgresDocumentRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class IRepostRepositoryImpl extends PostgresDocumentRepository<Repost> implements IRepostRepository {
    public IRepostRepositoryImpl(PostgresDocumentStore store) { super(store, Repost.class); }
    @Override public Optional<Repost> findByUserIdAndPostId(String userId, String postId) {
        return store.findOne(Query.query(new Criteria().andOperator(Criteria.where("userId").is(userId), Criteria.where("postId").is(postId))), Repost.class);
    }
    @Override public List<Repost> findByUserIdAndPostIdIn(String userId, List<String> postIds) {
        if (postIds == null || postIds.isEmpty()) return List.of();
        return store.find(Query.query(new Criteria().andOperator(Criteria.where("userId").is(userId), Criteria.where("postId").in(postIds))), Repost.class);
    }
    @Override public List<Repost> findByUserIdIn(List<String> userIds, Pageable pageable) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        Query query = Query.query(Criteria.where("userId").in(userIds));
        applyPage(query, pageable);
        return store.find(query, Repost.class);
    }
    @Override public List<Repost> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable) {
        Query query = Query.query(Criteria.where("userId").is(userId)).with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));
        applyPage(query, pageable);
        return store.find(query, Repost.class);
    }
    @Override public List<Repost> findByPostIdOrderByCreatedAtDesc(String postId, Pageable pageable) {
        Query query = Query.query(Criteria.where("postId").is(postId)).with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));
        applyPage(query, pageable);
        return store.find(query, Repost.class);
    }
    @Override public long countByPostId(String postId) { return store.count(Query.query(Criteria.where("postId").is(postId)), Repost.class); }
    @Override public long deleteByPostId(String postId) { return store.remove(Query.query(Criteria.where("postId").is(postId)), Repost.class).getDeletedCount(); }
    private void applyPage(Query query, Pageable pageable) { if (pageable != null && pageable.isPaged()) query.skip(pageable.getOffset()).limit(pageable.getPageSize()); if (pageable != null && pageable.getSort().isSorted()) query.with(pageable.getSort()); }
}
