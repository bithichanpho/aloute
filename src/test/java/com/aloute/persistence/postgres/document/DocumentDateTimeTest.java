package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.config.Jackson2ObjectMapperConfig;
import com.aloute.model.document.Post;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DocumentDateTimeTest {
    @Test
    void persistenceUsesIsoAndKeepsNanosecondsWithoutMutatingTheSharedApiMapper() throws Exception {
        ObjectMapper apiMapper = new Jackson2ObjectMapperConfig().jackson2ObjectMapper();
        var time = LocalDateTime.parse("2026-10-23T00:21:23.183036800");
        String before = apiMapper.writeValueAsString(time);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call -> {
            Object[] parameters = (Object[])call.getRawArguments()[1];
            var json = apiMapper.readTree((String) parameters[2]);
            assertTrue(json.get("createdAt").isTextual());
            assertEquals(time, LocalDateTime.parse(json.get("createdAt").textValue()));
            return 1;
        });
        var post = new Post(); post.setId("date-unit"); post.setCreatedAt(time);
        var store = new PostgresDocumentStore(jdbc, apiMapper);
        store.save(post); store.insert(post); assertTrue(store.insertIfAbsent(post));
        assertEquals(before, apiMapper.writeValueAsString(time));
        assertTrue(apiMapper.readTree(before).isArray());
        verify(jdbc,times(3)).update(anyString(),any(Object[].class));
    }
}
