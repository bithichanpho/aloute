package com.aloute.repository;

import com.aloute.model.document.Comment;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.repository.document.PostgresDocumentRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class ICommentRepositoryImpl extends PostgresDocumentRepository<Comment> implements ICommentRepository {
    public ICommentRepositoryImpl(PostgresDocumentStore store) { super(store, Comment.class); }
    @Override public List<Comment> findByPostIdOrderByCreatedAtAsc(String postId, Pageable pageable) {
        return store.find(page(Query.query(Criteria.where("postId").is(postId)), pageable), Comment.class);
    }
    @Override public List<Comment> findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAsc(String postId, Pageable pageable) {
        Criteria criteria = new Criteria().andOperator(Criteria.where("postId").is(postId), Criteria.where("parentCommentId").is(null));
        return store.find(page(Query.query(criteria), pageable), Comment.class);
    }
    @Override public long countByPostId(String postId) { return store.count(Query.query(Criteria.where("postId").is(postId)), Comment.class); }
    @Override public long countByParentCommentId(String parentCommentId) { return store.count(Query.query(Criteria.where("parentCommentId").is(parentCommentId)), Comment.class); }
    @Override public long deleteByPostId(String postId) { return store.remove(Query.query(Criteria.where("postId").is(postId)), Comment.class).getDeletedCount(); }
    private Query page(Query query, Pageable pageable) {
        query.with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")));
        if (pageable != null && pageable.isPaged()) query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return query;
    }
}
