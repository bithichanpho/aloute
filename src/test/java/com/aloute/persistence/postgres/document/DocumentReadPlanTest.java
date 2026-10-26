package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.data.domain.Sort;
import com.aloute.model.document.Post;
import com.fasterxml.jackson.databind.ObjectMapper;

class DocumentReadPlanTest {
    @Test void ownedCollectionIsConstantForPartialIndexesButEveryRequestValueRemainsBound() {
        String value="author'); DROP TABLE users; --";
        var jdbc=new JdbcTemplate(){
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                assertTrue(sql.contains("d.collection_name = 'posts'"));
                assertFalse(sql.contains(value));
                assertArrayEquals(new Object[]{value,50},args);
                return List.of();
            }
        };
        var store=new PostgresDocumentStore(jdbc,new ObjectMapper().findAndRegisterModules());
        assertTrue(store.find(Query.query(Criteria.where("userId").is(value)).with(Sort.by(Sort.Order.desc("createdAt"))).limit(50),Post.class).isEmpty());
    }
    @Test void annotationCannotInjectACollectionLiteral() {
        var jdbc=mock(JdbcTemplate.class);
        var store=new PostgresDocumentStore(jdbc,new ObjectMapper().findAndRegisterModules());
        assertThrows(IllegalArgumentException.class,()->store.find(new Query(),InvalidCollection.class));
        verifyNoInteractions(jdbc);
    }
    @PgDocument(collection="posts'; DROP TABLE users; --")
    static class InvalidCollection { }
}
