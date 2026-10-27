package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;
import com.aloute.model.document.Post;
import com.aloute.posts.infrastructure.PostHashtagQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PostHashtagQueryTest {
    @Test
    void legacyFallbackRequiresIndexedTextAndAnExactHashtagInOneParameterizedQuery() {
        var parameters = new ArrayList<Object>();
        String sql = new SqlCriteriaCompiler(new ObjectMapper(), Post.class)
                .compile(PostHashtagQuery.matching(" ALOUTE "), parameters);
        assertTrue(sql.contains("payload -> 'hashtags'"));
        assertTrue(sql.contains(" OR "));
        assertTrue(sql.contains("to_tsvector('simple', COALESCE(payload ->> 'content', '')) @@ plainto_tsquery('simple', ?)"));
        assertTrue(sql.contains(" AND (COALESCE(payload ->> 'content', '') ~ ?)"), sql);
        assertFalse(sql.contains("ALOUTE"));
        assertEquals(java.util.List.of("\"aloute\"", "aloute", "(?i)(^|[^[:alnum:]_])#aloute($|[^[:alnum:]_])"), parameters);
    }

    @Test
    void invalidHashtagsCannotTurnIntoRegexOrUnboundedQueries() {
        for (var tag : java.util.List.of("", ".*", "aloute|secret", "tag two", "x".repeat(51))) {
            var parameters = new ArrayList<Object>();
            String sql = new SqlCriteriaCompiler(new ObjectMapper(), Post.class)
                    .compile(PostHashtagQuery.matching(tag), parameters);
            assertEquals("FALSE", sql);
            assertTrue(parameters.isEmpty());
        }
    }
}
