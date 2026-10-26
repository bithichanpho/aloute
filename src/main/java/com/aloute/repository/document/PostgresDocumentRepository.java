package com.aloute.repository.document;

import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Base repository implementation for JSONB aggregates managed by the JDBC store. */
public abstract class PostgresDocumentRepository<T> implements DocumentCrudRepository<T, String> {
    protected final PostgresDocumentStore store;
    protected final Class<T> type;
    protected PostgresDocumentRepository(PostgresDocumentStore store, Class<T> type) {
        this.store = store;
        this.type = type;
    }

    @Override public T save(T entity) { return store.save(entity); }
    @Override public List<T> saveAll(Iterable<T> entities) { return store.saveAll(entities); }
    @Override public Optional<T> findById(String id) { return Optional.ofNullable(store.findById(id, type)); }
    @Override public boolean existsById(String id) {
        return store.exists(Query.query(Criteria.where("_id").is(id)), type);
    }
    @Override public List<T> findAll() { return store.findAll(type); }
    @Override public List<T> findAll(Sort sort) { return store.find(new Query().with(sort), type); }
    @Override public Page<T> findAll(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) return new PageImpl<>(findAll());
        Query query = new Query().with(pageable.getSort()).skip(pageable.getOffset()).limit(pageable.getPageSize());
        return new PageImpl<>(store.find(query, type), pageable, store.count(type));
    }
    @Override public List<T> findAllById(Iterable<String> ids) {
        List<String> values = new ArrayList<>();
        if (ids != null) ids.forEach(values::add);
        if (values.isEmpty()) return List.of();
        return store.find(Query.query(Criteria.where("_id").in(values)), type);
    }
    @Override public long count() { return store.count(type); }
    @Override public void delete(T entity) { store.remove(entity); }
    @Override public void deleteById(String id) { store.remove(Query.query(Criteria.where("_id").is(id)), type); }
    @Override public void deleteAll() { store.remove(new Query(), type); }
}
