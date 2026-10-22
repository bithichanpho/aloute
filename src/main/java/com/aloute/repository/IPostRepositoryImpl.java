package com.aloute.repository;

import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.repository.document.PostgresDocumentRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class IPostRepositoryImpl extends PostgresDocumentRepository<Post> implements IPostRepository {
    public IPostRepositoryImpl(PostgresDocumentStore store) { super(store, Post.class); }

    @Override public List<Post> findAllOriginalsByOrderByCreatedAtDesc(Pageable pageable) {
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("repostOfId").is(null),
                Criteria.where("moderationStatus").ne(ModerationStatus.HIDDEN));
        return store.find(page(pageable, criteria), Post.class);
    }

    @Override public List<Post> findByCommunityIdOrderByCreatedAtDesc(String communityId, Pageable pageable) {
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("communityId").is(communityId),
                Criteria.where("repostOfId").is(null),
                Criteria.where("moderationStatus").ne(ModerationStatus.HIDDEN),
                new Criteria().orOperator(
                        Criteria.where("communityPostStatus").is(null),
                        Criteria.where("communityPostStatus").is(CommunityPostStatus.APPROVED)));
        return store.find(page(pageable, criteria), Post.class);
    }

    @Override public List<Post> findCommunityPostsByStatus(String communityId, CommunityPostStatus status, Pageable pageable) {
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("communityId").is(communityId),
                Criteria.where("repostOfId").is(null),
                Criteria.where("communityPostStatus").is(status));
        return store.find(page(pageable, criteria), Post.class);
    }

    private Query page(Pageable pageable, Criteria criteria) {
        Query query = Query.query(criteria).with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));
        if (pageable != null && pageable.isPaged()) query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return query;
    }
}
