package com.aloute.persistence.postgres.document;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;

/** Search options for a PostgreSQL JSONB-backed aggregate. */
public class Query {
    private Criteria criteria;
    private Sort sort = Sort.unsorted();
    private int limit = -1;
    private long skip;

    public Query() { }

    public Query(Criteria criteria) { this.criteria = criteria; }

    public static Query query(Criteria criteria) { return new Query(criteria); }
    public Query addCriteria(Criteria criteria) {
        if (criteria == null) return this;
        if (this.criteria == null) this.criteria = criteria;
        else this.criteria.andOperator(criteria);
        return this;
    }
    public Query with(Sort sort) { this.sort = sort == null ? Sort.unsorted() : sort; return this; }
    public Query with(Pageable pageable) {
        if (pageable == null) return this;
        if (pageable.isPaged()) {
            this.skip = pageable.getOffset();
            this.limit = pageable.getPageSize();
        }
        this.sort = pageable.getSort().isSorted() ? pageable.getSort() : Sort.unsorted();
        return this;
    }
    public Query limit(int limit) { this.limit = limit < 0 ? -1 : limit; return this; }
    public Query skip(long skip) { this.skip = Math.max(0, skip); return this; }

    public Criteria criteria() { return criteria; }
    public Sort sort() { return sort; }
    public int limit() { return limit; }
    public long skip() { return skip; }
}
