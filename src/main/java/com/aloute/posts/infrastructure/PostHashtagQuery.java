package com.aloute.posts.infrastructure;

import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.TextCriteria;
import java.util.Locale;

/** Posts-owned compatibility read for legacy/demo records without materialized hashtags. */
public final class PostHashtagQuery {
    private PostHashtagQuery() {}

    public static Criteria matching(String tag) {
        String normalized = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        // Same alphabet/length as ContentTags; never interpret user input as a regex.
        if (!normalized.matches("[\\p{L}\\p{N}_]{1,50}")) {
            return Criteria.where("_id").in(java.util.List.of());
        }
        return new Criteria().orOperator(
                Criteria.where("hashtags").is(normalized),
                new Criteria().andOperator(
                        TextCriteria.forLanguage("none").matching(normalized),
                        Criteria.where("content").regex(
                                "(?i)(^|[^[:alnum:]_])#" + normalized + "($|[^[:alnum:]_])")));
    }
}
