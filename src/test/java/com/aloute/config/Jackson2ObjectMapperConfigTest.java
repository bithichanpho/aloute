package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Jackson2ObjectMapperConfigTest {

    private final ObjectMapper mapper = new Jackson2ObjectMapperConfig().jackson2ObjectMapper();

    @Test
    void writesDatesAsIsoStringsSoSqlTimestampCastsSucceed() {
        LocalDateTime local = LocalDateTime.of(2026, 10, 23, 0, 21, 23, 183036800);
        Instant instant = Instant.parse("2026-10-23T00:21:23.183036800Z");

        JsonNode json = mapper.valueToTree(Map.of("createdAt", local, "nextAttemptAt", instant));

        assertEquals("2026-10-23T00:21:23.1830368", json.get("createdAt").textValue());
        assertEquals("2026-10-23T00:21:23.183036800Z", json.get("nextAttemptAt").textValue());
    }

    @Test
    void stillReadsLegacyArrayAndNumericDates() throws Exception {
        record Legacy(LocalDateTime createdAt, Instant nextAttemptAt) {}

        Legacy legacy = mapper.readValue(
                "{\"createdAt\":[2026,10,23,0,21,23,183036800],\"nextAttemptAt\":1792714883.183036800}",
                Legacy.class);

        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 21, 23, 183036800), legacy.createdAt());
        assertEquals(Instant.parse("2026-10-23T00:21:23.183036800Z"), legacy.nextAttemptAt());
    }
}
