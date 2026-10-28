package com.aloute.service;

import com.aloute.common.util.LikePatternUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Substring user search backed by the pg_trgm GIN indexes on {@code lower(username)} and
 * {@code lower(full_name)} (migration V20261023).
 *
 * <p>The expressions below must stay {@code lower(column) LIKE ...} so they match those indexes.
 */
@Component
public class UserSearchQuery {

    @PersistenceContext private EntityManager entityManager;

    /**
     * Returns up to {@code limit} matching user ids, newest first.
     *
     * <p>Why the {@code set_config} call: this query is {@code ORDER BY id DESC LIMIT n}. After a
     * few executions of the same server-side prepared statement, PostgreSQL may switch to a generic
     * plan. A generic plan cannot see the search term, assumes the match is common, and walks the
     * primary key backwards filtering every row, which ignores the trigram index and degrades to a
     * full scan for rare terms (measured 250 ms vs 0.3 ms on 300k users). Forcing a custom plan for
     * this transaction lets the planner pick per term: the trigram index for rare terms, and the
     * cheap primary-key walk for very common ones. {@code is_local = true} scopes it to this
     * transaction only. Requires PostgreSQL 12+.
     */
    @Transactional(readOnly = true)
    public List<Long> findIds(String term, int limit) {
        return findIds(term, 0, limit);
    }

    /** Returns one bounded result page without materializing every matching account id. */
    @Transactional(readOnly = true)
    public List<Long> findIds(String term, int page, int size) {
        return findIds(term, page, size, null);
    }

    /**
     * Returns one bounded result page, optionally excluding the current account.
     * Exclusion occurs in the database so the current account does not consume a page slot.
     */
    @Transactional(readOnly = true)
    public List<Long> findIds(String term, int page, int size, Long excludedUserId) {
        entityManager
                .createNativeQuery("select set_config('plan_cache_mode', 'force_custom_plan', true)")
                .getSingleResult();

        String jpql = "select u.id from User u"
                + " where (lower(u.username) like lower(:pattern) escape '!'"
                + " or lower(u.fullName) like lower(:pattern) escape '!')";
        if (excludedUserId != null) {
            jpql += " and u.id <> :excludedUserId";
        }
        jpql += " order by u.id desc";

        var query = entityManager.createQuery(jpql, Long.class)
                .setParameter("pattern", LikePatternUtil.contains(term));
        if (excludedUserId != null) {
            query.setParameter("excludedUserId", excludedUserId);
        }
        return query
                .setFirstResult(Math.multiplyExact(page, size))
                .setMaxResults(size)
                .getResultList();
    }
}
