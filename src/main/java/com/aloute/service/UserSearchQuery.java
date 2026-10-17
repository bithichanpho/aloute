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
        entityManager
                .createNativeQuery("select set_config('plan_cache_mode', 'force_custom_plan', true)")
                .getSingleResult();
        return entityManager
                .createQuery(
                        "select u.id from User u"
                                + " where lower(u.username) like lower(:pattern) escape '!'"
                                + " or lower(u.fullName) like lower(:pattern) escape '!'"
                                + " order by u.id desc",
                        Long.class)
                .setParameter("pattern", LikePatternUtil.contains(term))
                .setMaxResults(limit)
                .getResultList();
    }
}
