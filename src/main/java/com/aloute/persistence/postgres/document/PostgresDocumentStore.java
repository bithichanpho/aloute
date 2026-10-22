package com.aloute.persistence.postgres.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence adapter for document-shaped aggregates stored in PostgreSQL JSONB.
 * Relational aggregates remain managed by Spring Data JPA; this adapter is deliberately
 * PostgreSQL-only and uses the same DataSource/transaction as JPA.
 */
@Repository
public class PostgresDocumentStore {
    private static final String TABLE = "app_document_records";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectMapper persistenceMapper;

    public PostgresDocumentStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        // Persistence of aggregates must include internal state even when API JSON hides it.
        this.persistenceMapper = objectMapper.copy().disable(MapperFeature.USE_ANNOTATIONS);
    }

    public <T> T save(T entity) {
        if (entity == null) throw new IllegalArgumentException("entity cannot be null");
        String collection = collectionName(entity.getClass());
        Object id = readId(entity);
        if (id == null) {
            id = generateId(entity);
            writeId(entity, id);
        }
        String documentId = id.toString();
        String payload = serialize(entity);
        jdbcTemplate.update("""
                INSERT INTO app_document_records(collection_name, document_id, payload, created_at, updated_at)
                VALUES (?, ?, CAST(? AS jsonb), now(), now())
                ON CONFLICT (collection_name, document_id)
                DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()
                """, collection, documentId, payload);
        return entity;
    }

    /** Inserts only; a duplicate key is intentionally surfaced to the caller. */
    public <T> T insert(T entity) {
        if (entity == null) throw new IllegalArgumentException("entity cannot be null");
        String collection = collectionName(entity.getClass());
        Object id = readId(entity);
        if (id == null) {
            id = generateId(entity);
            writeId(entity, id);
        }
        jdbcTemplate.update("""
                INSERT INTO app_document_records(collection_name, document_id, payload, created_at, updated_at)
                VALUES (?, ?, CAST(? AS jsonb), now(), now())
                """, collection, id.toString(), serialize(entity));
        return entity;
    }

    public <T> List<T> saveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        if (entities == null) return saved;
        for (T entity : entities) saved.add(save(entity));
        return saved;
    }

    public <T> T findById(Object id, Class<T> type) {
        if (id == null) return null;
        List<T> rows = jdbcTemplate.query(
                "SELECT payload::text FROM app_document_records WHERE collection_name = ? AND document_id = ?",
                (rs, rowNum) -> deserialize(rs.getString(1), type), collectionName(type), id.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public <T> Optional<T> findOne(Query query, Class<T> type) {
        Query effective = query == null ? new Query() : query;
        effective.limit(1);
        List<T> results = find(effective, type);
        return results.stream().findFirst();
    }

    public <T> List<T> find(Query query, Class<T> type) {
        Query effective = query == null ? new Query() : query;
        List<Object> parameters = new ArrayList<>();
        String where = compileWhere(effective.criteria(), type, parameters);
        StringBuilder sql = new StringBuilder("SELECT d.payload::text FROM ").append(TABLE)
                .append(" d WHERE d.collection_name = ? AND (").append(where).append(")");
        List<Object> bound = new ArrayList<>();
        bound.add(collectionName(type));
        bound.addAll(parameters);
        String order = orderBy(effective.sort(), type, "d");
        if (!order.isEmpty()) sql.append(" ORDER BY ").append(order);
        appendLimitOffset(sql, bound, effective.limit(), effective.skip());
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> deserialize(rs.getString(1), type), bound.toArray());
    }

    public <T> List<T> findAll(Class<T> type) { return find(new Query(), type); }

    public <T> boolean exists(Query query, Class<T> type) {
        return count(query, type) > 0;
    }

    public <T> long count(Class<T> type) { return count(new Query(), type); }

    public <T> long count(Query query, Class<T> type) {
        Query effective = query == null ? new Query() : query;
        List<Object> criterionParameters = new ArrayList<>();
        String where = compileWhere(effective.criteria(), type, criterionParameters);
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(type));
        parameters.addAll(criterionParameters);
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE
                + " d WHERE d.collection_name = ? AND (" + where + ")", Long.class, parameters.toArray());
        return count == null ? 0L : count;
    }

    public <T> DeleteResult remove(Query query, Class<T> type) {
        Query effective = query == null ? new Query() : query;
        List<Object> criterionParameters = new ArrayList<>();
        String where = compileWhere(effective.criteria(), type, criterionParameters);
        StringBuilder target = new StringBuilder("WITH target AS (SELECT d.document_id FROM ")
                .append(TABLE).append(" d WHERE d.collection_name = ? AND (").append(where).append(")");
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(type));
        parameters.addAll(criterionParameters);
        appendOrder(target, effective.sort(), type, "d");
        appendLimitOffset(target, parameters, effective.limit(), effective.skip());
        target.append(" FOR UPDATE) DELETE FROM ").append(TABLE)
                .append(" d USING target t WHERE d.collection_name = ? AND d.document_id = t.document_id");
        parameters.add(collectionName(type));
        return new DeleteResult(jdbcTemplate.update(target.toString(), parameters.toArray()));
    }

    public void remove(Object entity) {
        if (entity == null) return;
        Object id = readId(entity);
        if (id != null) jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE collection_name = ? AND document_id = ?",
                collectionName(entity.getClass()), id.toString());
    }

    @Transactional
    public <T> UpdateResult updateFirst(Query query, Update update, Class<T> type) {
        return update(query, update, type, true);
    }

    @Transactional
    public <T> UpdateResult updateMulti(Query query, Update update, Class<T> type) {
        return update(query, update, type, false);
    }

    private <T> UpdateResult update(Query query, Update update, Class<T> type, boolean firstOnly) {
        if (update == null || update.operations().isEmpty()) return new UpdateResult(0, 0);
        Query effective = query == null ? new Query() : query;
        List<Object> criteriaParameters = new ArrayList<>();
        String where = compileWhere(effective.criteria(), type, criteriaParameters);
        StringBuilder sql = new StringBuilder("WITH target AS (SELECT d.document_id, d.payload FROM ")
                .append(TABLE).append(" d WHERE d.collection_name = ? AND (").append(where).append(")");
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(type));
        parameters.addAll(criteriaParameters);
        appendOrder(sql, effective.sort(), type, "d");
        int limit = firstOnly ? 1 : effective.limit();
        appendLimitOffset(sql, parameters, limit, effective.skip());
        sql.append(" FOR UPDATE)");

        String priorCte = "target";
        int index = 0;
        for (Update.Operation operation : update.operations()) {
            String currentCte = "u" + (++index);
            sql.append(", ").append(currentCte).append(" AS (SELECT document_id, ")
                    .append(updateExpression(operation, parameters, priorCte)).append(" AS payload FROM ")
                    .append(priorCte).append(")");
            priorCte = currentCte;
        }
        sql.append(" UPDATE ").append(TABLE).append(" d SET payload = final.payload, updated_at = now() FROM ")
                .append(priorCte).append(" final WHERE d.collection_name = ? AND d.document_id = final.document_id");
        parameters.add(collectionName(type));
        int changed = jdbcTemplate.update(sql.toString(), parameters.toArray());
        return new UpdateResult(changed, changed);
    }

    @Transactional
    public <T> T findAndModify(Query query, Update update, FindAndModifyOptions options, Class<T> type) {
        Query effective = query == null ? new Query() : query;
        T before = lockFirst(effective, type);
        if (before == null) return null;
        UpdateResult result = updateFirst(effective, update, type);
        if (result.getMatchedCount() == 0) return null;
        if (options != null && options.isReturnNew()) {
            Object id = readId(before);
            return findById(id, type);
        }
        return before;
    }

    private <T> T lockFirst(Query query, Class<T> type) {
        List<Object> criterionParameters = new ArrayList<>();
        String where = compileWhere(query.criteria(), type, criterionParameters);
        StringBuilder sql = new StringBuilder("SELECT d.payload::text FROM ").append(TABLE)
                .append(" d WHERE d.collection_name = ? AND (").append(where).append(")");
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(type));
        parameters.addAll(criterionParameters);
        appendOrder(sql, query.sort(), type, "d");
        sql.append(" LIMIT 1 FOR UPDATE");
        List<T> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> deserialize(rs.getString(1), type), parameters.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public <T, R> List<R> findDistinct(Query query, String field, Class<T> sourceType, Class<R> resultType) {
        String safe = safeField(field);
        Query effective = query == null ? new Query() : query;
        List<Object> criterionParameters = new ArrayList<>();
        String where = compileWhere(effective.criteria(), sourceType, criterionParameters);
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(sourceType));
        parameters.addAll(criterionParameters);
        List<?> raw = jdbcTemplate.query("SELECT DISTINCT d.payload ->> '" + safe + "' FROM " + TABLE
                        + " d WHERE d.collection_name = ? AND (" + where + ") AND d.payload ->> '" + safe + "' IS NOT NULL",
                (rs, rowNum) -> rs.getString(1), parameters.toArray());
        List<R> result = new ArrayList<>();
        for (Object value : raw) result.add(objectMapper.convertValue(value, resultType));
        return result;
    }

    /** SQL GROUP BY helper used for unread counters and reaction summaries. */
    public <T> Map<List<String>, Long> countGroupedBy(Query query, Class<T> type, String... fields) {
        if (fields == null || fields.length == 0) return Map.of();
        List<String> safeFields = java.util.Arrays.stream(fields).map(this::safeField).toList();
        String selected = safeFields.stream().map(f -> "d.payload ->> '" + f + "'").collect(java.util.stream.Collectors.joining(", "));
        String groupBy = safeFields.stream().map(f -> "d.payload ->> '" + f + "'").collect(java.util.stream.Collectors.joining(", "));
        Query effective = query == null ? new Query() : query;
        List<Object> criterionParameters = new ArrayList<>();
        String where = compileWhere(effective.criteria(), type, criterionParameters);
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(type));
        parameters.addAll(criterionParameters);
        String sql = "SELECT " + selected + ", count(*) FROM " + TABLE + " d WHERE d.collection_name = ? AND ("
                + where + ") GROUP BY " + groupBy;
        return jdbcTemplate.query(sql, rs -> {
            Map<List<String>, Long> rows = new LinkedHashMap<>();
            while (rs.next()) {
                List<String> key = new ArrayList<>();
                for (int i = 0; i < safeFields.size(); i++) key.add(rs.getString(i + 1));
                rows.put(java.util.Collections.unmodifiableList(new ArrayList<>(key)), rs.getLong(safeFields.size() + 1));
            }
            return rows;
        }, parameters.toArray());
    }

    /** Returns the most recent row per field using PostgreSQL row_number() and applies a post-group cursor. */
    public <T> List<T> findLatestPerField(Query baseQuery, String groupField, Query afterQuery,
                                           Class<T> type, Sort sort, int limit) {
        String group = safeField(groupField);
        Query before = baseQuery == null ? new Query() : baseQuery;
        Query after = afterQuery == null ? new Query() : afterQuery;
        List<Object> beforeParams = new ArrayList<>();
        String beforeWhere = compileWhere(before.criteria(), type, beforeParams);
        List<Object> afterParams = new ArrayList<>();
        String afterWhere = compileWhere(after.criteria(), type, afterParams);
        String order = orderBy(sort, type, "d");
        if (order.isBlank()) order = "d.payload ->> 'createdAt' DESC, d.document_id DESC";
        List<Object> parameters = new ArrayList<>();
        parameters.add(collectionName(type));
        parameters.addAll(beforeParams);
        parameters.addAll(afterParams);
        parameters.add(Math.max(1, limit));
        String sql = "WITH ranked AS (SELECT d.document_id, d.payload, row_number() OVER (PARTITION BY d.payload ->> '" + group
                + "' ORDER BY " + order + ") AS rn FROM " + TABLE + " d WHERE d.collection_name = ? AND (" + beforeWhere
                + ")), latest AS (SELECT document_id, payload FROM ranked WHERE rn = 1) SELECT d.payload::text FROM latest d WHERE ("
                + afterWhere + ") ORDER BY " + order + " LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> deserialize(rs.getString(1), type), parameters.toArray());
    }

    private String updateExpression(Update.Operation op, List<Object> parameters, String priorCte) {
        String field = safePath(op.field());
        return switch (op.kind()) {
            case SET -> {
                parameters.add(field);
                parameters.add(serialize(op.value()));
                yield "jsonb_set(payload, string_to_array(?, '.'), CAST(? AS jsonb), true)";
            }
            case UNSET -> {
                parameters.add(field);
                yield "payload #- string_to_array(?, '.')";
            }
            case INC -> {
                parameters.add(field);
                parameters.add(field);
                parameters.add(op.value());
                yield "jsonb_set(payload, string_to_array(?, '.'), to_jsonb(COALESCE(NULLIF(payload #>> string_to_array(?, '.'), '')::numeric, 0) + CAST(? AS numeric)), true)";
            }
            case ADD_TO_SET -> {
                parameters.add(field);
                parameters.add(field);
                parameters.add(field);
                parameters.add(serialize(op.value()));
                parameters.add(serialize(op.value()));
                yield "jsonb_set(payload, string_to_array(?, '.'), COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) || "
                        + "(CASE WHEN COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) @> CAST(? AS jsonb) THEN '[]'::jsonb ELSE CAST(? AS jsonb) END), true)";
            }
            case PULL -> {
                parameters.add(field);
                parameters.add(field);
                parameters.add(op.value() == null ? null : op.value().toString());
                yield "jsonb_set(payload, string_to_array(?, '.'), COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) - CAST(? AS text), true)";
            }
            case PUSH -> {
                List<?> values = op.value() instanceof List<?> list ? list : List.of(op.value());
                String jsonValues = serialize(values);
                if (op.slice() == null || op.slice() >= 0) {
                    parameters.add(field);
                    parameters.add(field);
                    parameters.add(jsonValues);
                    if (op.slice() == null) {
                        yield "jsonb_set(payload, string_to_array(?, '.'), COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) || CAST(? AS jsonb), true)";
                    }
                    // Positive slice keeps the first N items.
                    parameters.add(op.slice());
                    yield "jsonb_set(payload, string_to_array(?, '.'), (SELECT COALESCE(jsonb_agg(v ORDER BY ord), '[]'::jsonb) FROM "
                            + "jsonb_array_elements(COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) || CAST(? AS jsonb)) WITH ORDINALITY a(v, ord) "
                            + "WHERE ord <= ?), true)";
                }
                int keep = Math.abs(op.slice());
                parameters.add(field);
                parameters.add(field);
                parameters.add(jsonValues);
                parameters.add(field);
                parameters.add(jsonValues);
                parameters.add(keep);
                yield "jsonb_set(payload, string_to_array(?, '.'), (SELECT COALESCE(jsonb_agg(v ORDER BY ord), '[]'::jsonb) FROM "
                        + "jsonb_array_elements(COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) || CAST(? AS jsonb)) WITH ORDINALITY a(v, ord) "
                        + "WHERE ord > jsonb_array_length(COALESCE(payload #> string_to_array(?, '.'), '[]'::jsonb) || CAST(? AS jsonb)) - ?), true)";
            }
        };
    }

    private String compileWhere(Criteria criteria, Class<?> type, List<Object> parameters) {
        return new SqlCriteriaCompiler(objectMapper, type).compile(criteria, parameters);
    }

    private String orderBy(Sort sort, Class<?> type, String alias) {
        if (sort == null || sort.isUnsorted()) return "";
        List<String> items = new ArrayList<>();
        for (Sort.Order order : sort) {
            String expr = new SqlCriteriaCompiler(objectMapper, type).sortExpression(order.getProperty(), alias);
            items.add(expr + (order.isIgnoreCase() ? "" : "") + " " + order.getDirection().name());
        }
        return String.join(", ", items);
    }

    private void appendOrder(StringBuilder sql, Sort sort, Class<?> type, String alias) {
        String order = orderBy(sort, type, alias);
        if (!order.isBlank()) sql.append(" ORDER BY ").append(order);
    }

    private void appendLimitOffset(StringBuilder sql, List<Object> parameters, int limit, long offset) {
        if (limit >= 0) { sql.append(" LIMIT ?"); parameters.add(limit); }
        else if (offset > 0) sql.append(" LIMIT ALL");
        if (offset > 0) { sql.append(" OFFSET ?"); parameters.add(offset); }
    }

    private String collectionName(Class<?> type) {
        PgDocument annotation = type.getAnnotation(PgDocument.class);
        if (annotation == null || annotation.collection().isBlank()) {
            throw new IllegalArgumentException("Class is not a PostgreSQL document model: " + type.getName());
        }
        if (!annotation.collection().matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid document collection name");
        }
        return annotation.collection();
    }

    private String safePath(String field) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("Update field is required");
        String[] parts = field.split("\\.", -1);
        for (String part : parts) {
            if (!part.matches("[A-Za-z][A-Za-z0-9]*")) {
                throw new IllegalArgumentException("Unsupported update field path: " + field);
            }
        }
        return String.join(".", parts);
    }

    private String safeField(String field) {
        if (field == null || !(field.equals("id") || field.equals("_id") || field.matches("[A-Za-z][A-Za-z0-9]*"))) {
            throw new IllegalArgumentException("Unsupported document field: " + field);
        }
        return field.equals("_id") ? "id" : field;
    }

    private Object readId(Object entity) {
        try {
            Method method = entity.getClass().getMethod("getId");
            return method.invoke(entity);
        } catch (ReflectiveOperationException ignored) {
            return readField(entity, "id");
        }
    }

    private Object generateId(Object entity) {
        Field field = idField(entity.getClass());
        if (field != null && (field.getType() == Long.class || field.getType() == long.class)) {
            throw new IllegalStateException("Numeric document id must be supplied by the owning user: " + entity.getClass().getSimpleName());
        }
        return UUID.randomUUID().toString();
    }

    private void writeId(Object entity, Object id) {
        try {
            Method setter = entity.getClass().getMethod("setId", id.getClass());
            setter.invoke(entity, id);
            return;
        } catch (ReflectiveOperationException ignored) { }
        Field field = idField(entity.getClass());
        if (field == null) throw new IllegalStateException("Document id field is missing: " + entity.getClass().getName());
        try { field.setAccessible(true); field.set(entity, id); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot assign document id", e); }
    }

    private Field idField(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try { return current.getDeclaredField("id"); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private Object readField(Object target, String name) {
        Field field = idField(target.getClass());
        if (field == null) return null;
        try { field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot read document id", e); }
    }

    private String serialize(Object value) {
        try { return persistenceMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize PostgreSQL document", e); }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try { return persistenceMapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot deserialize PostgreSQL document " + type.getSimpleName(), e); }
    }
}
