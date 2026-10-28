package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.model.document.Post;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL only, synthetic rows in a rollback-only transaction. */
class DocumentDateTimeIT {
    private JdbcTemplate jdbc;
    private PostgresDocumentStore store;
    private TransactionTemplate transactions;
    private String owner;

    @BeforeEach
    void database() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url != null, "Disposable PostgreSQL is required");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aloute_quality_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Refusing any database except a local aloute_quality_* test database");
        }
        var source = new DriverManagerDataSource(url, System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"),
                System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        store = new PostgresDocumentStore(jdbc, new ObjectMapper().findAndRegisterModules());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        owner = "datetime-it-" + UUID.randomUUID();
    }

    @Test
    void mixedArrayAndIsoSortAndKeysetKeepAllNineFractionDigits() {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            raw("a-newer", "[2026,10,23,0,21,23,183036801]");
            raw("z-older", "\"2026-10-23T00:21:23.183036800\"");
            var rows = store.find(query().with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))), Post.class);
            assertEquals(List.of("a-newer", "z-older"), rows.stream().map(Post::getId).toList());
            var cursor = LocalDateTime.parse("2026-10-23T00:21:23.183036801");
            assertEquals(List.of("z-older"), store.find(query().addCriteria(Criteria.where("createdAt").lt(cursor)),
                    Post.class).stream().map(Post::getId).toList());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"yesterday\"", "[2026,2,30,0,0]", "[2026,10,23,0,21,23,1000000000]",
            "[2026,10,23]", "{}", "true", "[2026,10,23,0,21,null]", "[2026,10,23,0,21,2.5]",
            "\"2026-02-30T00:00:00\"", "\"2026-10-23T00:00:00Z\"", "\"2026-10-23T00:00:00.123456890\"",
            "\"+2026-10-23T00:00\"", "\"10000-01-01T00:00\"", "\"-0000-01-01T00:00\""})
    void malformedAndNullValuesHaveNoTemporalKeyAndNeverEnterFeed(String value) {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            raw("date-invalid", value);
            raw("date-valid", "[2026,10,23,0,21]");
            assertNull(jdbc.queryForObject("SELECT aloute_jsonb_local_datetime_key(?::jsonb)", Object.class, value));
            assertEquals(List.of("date-valid"), store.find(query().addCriteria(Criteria.where("createdAt").validDateTime())
                    .with(Sort.by(Sort.Order.desc("createdAt"))), Post.class).stream().map(Post::getId).toList());
            assertEquals(2, store.count(query(), Post.class)); // Invalid payload is preserved, not deleted/repaired.
        });
    }

    @Test
    void writesAndAtomicUpdatesUseIsoWithoutChangingInjectedApiMapper() {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            LocalDateTime time = LocalDateTime.parse("2026-10-23T00:21:23.183036801");
            for (String method : List.of("save", "insert", "insertIfAbsent")) {
                var post = new Post(); post.setId("date-" + method); post.setUserId(owner); post.setCreatedAt(time);
                switch (method) {
                    case "save" -> store.save(post);
                    case "insert" -> store.insert(post);
                    default -> assertTrue(store.insertIfAbsent(post));
                }
                assertEquals("string", jdbc.queryForObject("SELECT jsonb_typeof(payload->'createdAt') FROM app_document_records WHERE collection_name='posts' AND document_id=?", String.class, post.getId()));
                assertEquals(time, store.findById(post.getId(), Post.class).getCreatedAt());
            }
            var revision = new com.aloute.model.document.PostRevision();
            Instant instant = Instant.parse("2026-10-23T00:21:23.12345689Z"); revision.setCreatedAt(instant);
            store.updateFirst(query(), new Update().set("updatedAt", time)
                    .push("editHistory").each(revision), Post.class);
            Post updated = store.findOne(query().addCriteria(Criteria.where("updatedAt").is(time)), Post.class).orElseThrow();
            assertNotNull(updated); assertEquals(time, updated.getUpdatedAt());
            assertEquals(instant, updated.getEditHistory().getFirst().getCreatedAt());
            assertEquals("string", jdbc.queryForObject("SELECT jsonb_typeof(payload->'editHistory'->0->'createdAt') FROM app_document_records WHERE collection_name='posts' AND document_id=?", String.class, updated.getId()));
            assertTrue(new ObjectMapper().findAndRegisterModules().valueToTree(time).isArray());
        });
    }

    @Test
    void comparisonOperatorsAndOffsetPaginationShareTheSameKey() {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            raw("date-a", "[2026,10,23,0,21,23,183036800]");
            raw("date-b", "\"2026-10-23T00:21:23.1830368\"");
            raw("date-c", "\"2026-10-23T00:21:23.183036801\"");
            var time = LocalDateTime.parse("2026-10-23T00:21:23.183036800");
            assertEquals(2, store.count(query().addCriteria(Criteria.where("createdAt").is(time)), Post.class));
            assertEquals(1, store.count(query().addCriteria(Criteria.where("createdAt").ne(time)), Post.class));
            assertEquals(1, store.count(query().addCriteria(Criteria.where("createdAt").gt(time)), Post.class));
            assertEquals(3, store.count(query().addCriteria(Criteria.where("createdAt").gte(time)), Post.class));
            assertEquals(2, store.count(query().addCriteria(Criteria.where("createdAt").lte(time)), Post.class));
            assertEquals(2, store.count(query().addCriteria(Criteria.where("createdAt").in(time)), Post.class));
            assertEquals(1, store.count(query().addCriteria(Criteria.where("createdAt").nin(time)), Post.class));
            assertEquals(List.of("date-a"), store.find(query().with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))).skip(2).limit(1), Post.class).stream().map(Post::getId).toList());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-02-29T00:00", "-0001-03-01T10:11:12.000000001", "2000-02-29T23:59:59.999999999",
            "1900-03-01T00:00", "2026-10-23T00:21:23.183036800", "+999999999-12-31T23:59:59.999999999", "-999999999-01-01T00:00"})
    void localDateTimeKeysMatchJavaAtCalendarAndPrecisionBoundaries(String text) throws Exception {
        var value = LocalDateTime.parse(text);
        String array = new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        assertTrue(jdbc.queryForObject("SELECT aloute_jsonb_local_datetime_key(?::jsonb) = aloute_jsonb_local_datetime_key(to_jsonb(?::text))", Boolean.class, array, text));
        String instant = value.toInstant(java.time.ZoneOffset.UTC).toString();
        BigDecimal expected = BigDecimal.valueOf(value.toEpochSecond(java.time.ZoneOffset.UTC)).add(BigDecimal.valueOf(value.getNano(),9));
        assertEquals(0, expected.compareTo(jdbc.queryForObject("SELECT aloute_jsonb_instant_key(to_jsonb(?::text))", BigDecimal.class, instant)));
    }

    @Test
    void instantNumericLegacyAndIsoOffsetsKeepExactUtcNanosecondsAcrossSessionTimezones() {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            for (String timezone : List.of("UTC", "America/New_York", "Asia/Ho_Chi_Minh")) {
                jdbc.execute("SET LOCAL TIME ZONE '" + timezone + "'");
                assertTrue(jdbc.queryForObject("SELECT aloute_jsonb_instant_key('1792714883.183036801'::jsonb) = aloute_jsonb_instant_key('\"2026-10-23T07:21:23.183036801+07:00\"'::jsonb)", Boolean.class));
                assertTrue(jdbc.queryForObject("SELECT aloute_jsonb_instant_key('\"1969-12-31T23:59:59.999999999Z\"'::jsonb) = -0.000000001", Boolean.class));
                assertNull(jdbc.queryForObject("SELECT aloute_jsonb_instant_key('\"2026-10-23T00:00:00\"'::jsonb)", Object.class));
                assertNull(jdbc.queryForObject("SELECT aloute_jsonb_instant_key('\"2026-02-30T00:00:00Z\"'::jsonb)", Object.class));
            }
            var job = new com.aloute.model.document.AccountCleanupJob();
            job.setId("date-job"); job.setUserId(987654321L); job.setNextAttemptAt(Instant.parse("2026-10-23T00:21:23.183036801Z"));
            store.save(job);
            assertEquals(1, store.count(Query.query(Criteria.where("id").is(job.getId()).and("nextAttemptAt").is(job.getNextAttemptAt())), job.getClass()));
            assertEquals(0, store.count(Query.query(Criteria.where("id").is(job.getId()).and("nextAttemptAt").lt(job.getNextAttemptAt())), job.getClass()));
        });
    }

    @Test
    void instantKeysMatchJavaForExtremesAndDeterministicRandomCalendars() {
        var random = new java.util.Random(20261024L);
        var values = new java.util.ArrayList<Instant>(List.of(Instant.MIN, Instant.MAX, Instant.EPOCH));
        for (int i=0; i<100; i++) {
            values.add(LocalDateTime.of(random.nextInt(1999999999)-999999999,random.nextInt(12)+1,
                    random.nextInt(28)+1,random.nextInt(24),random.nextInt(60),random.nextInt(60),random.nextInt(1000000000))
                    .toInstant(java.time.ZoneOffset.ofHours(random.nextInt(37)-18)));
        }
        for (Instant value : values) {
            BigDecimal expected = BigDecimal.valueOf(value.getEpochSecond()).add(BigDecimal.valueOf(value.getNano(),9));
            assertEquals(0,expected.compareTo(jdbc.queryForObject("SELECT aloute_jsonb_instant_key(to_jsonb(?::text))",BigDecimal.class,value.toString())),value.toString());
        }
    }

    private Query query() { return Query.query(Criteria.where("userId").is(owner)); }

    private void raw(String id, String value) {
        jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('posts',?,CAST(? AS jsonb))",
                id, "{\"id\":\"" + id + "\",\"userId\":\"" + owner + "\",\"createdAt\":" + value + "}");
    }
}
