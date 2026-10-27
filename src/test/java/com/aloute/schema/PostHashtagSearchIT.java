package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.aloute.model.document.Post;
import com.aloute.persistence.postgres.document.*;
import com.aloute.posts.infrastructure.PostHashtagQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exact legacy-tag matching and pagination against an explicitly opted-in disposable local DB. */
class PostHashtagSearchIT {
    @Test
    void hashtagReadsLegacyAndMaterializedPostsWithoutMatchingPlainWordsOrLongerTags() {
        try (var db = DisposablePostgres.create()) {
            db.migrations().load().migrate();
            var store = new PostgresDocumentStore(db.jdbc, new ObjectMapper().findAndRegisterModules());
            save(store, "legacy", "Xin chào #ALOUTE!", List.of());
            save(store, "new", "Tin mới #aloute", List.of("aloute"));
            save(store, "plain", "ALOUTE", List.of());
            save(store, "long", "#ALOUTE_more #aloute123", List.of());
            save(store, "joined", "abc#ALOUTE", List.of());
            save(store, "unicode", "#ĐỒ_ÁN #a_b", List.of());
            save(store, "unicode-long", "#đồ_án_mới #a_b_c", List.of());
            var query = Query.query(PostHashtagQuery.matching("ALOUTE"))
                    .with(org.springframework.data.domain.Sort.by("id"));
            assertEquals(List.of("legacy", "new"), store.find(query, Post.class).stream().map(Post::getId).toList());
            assertEquals(List.of("new"), store.find(query.skip(1).limit(1), Post.class).stream().map(Post::getId).toList());
            for (var tag : List.of("đồ_án", "a_b")) {
                assertEquals(List.of("unicode"), store.find(Query.query(PostHashtagQuery.matching(tag)), Post.class)
                        .stream().map(Post::getId).toList());
            }
        }
    }

    private void save(PostgresDocumentStore store, String id, String content, List<String> tags) {
        var post = new Post(); post.setId(id); post.setUserId("1"); post.setContent(content);
        post.setHashtags(tags); store.save(post);
    }
}
