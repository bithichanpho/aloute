package com.aloute.repository.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

/** Common CRUD contract for document-shaped aggregates persisted in PostgreSQL JSONB. */
public interface DocumentCrudRepository<T, ID> {
    T save(T entity);
    List<T> saveAll(Iterable<T> entities);
    Optional<T> findById(ID id);
    boolean existsById(ID id);
    List<T> findAll();
    List<T> findAll(Sort sort);
    Page<T> findAll(Pageable pageable);
    List<T> findAllById(Iterable<ID> ids);
    long count();
    void delete(T entity);
    void deleteById(ID id);
    void deleteAll();
}
