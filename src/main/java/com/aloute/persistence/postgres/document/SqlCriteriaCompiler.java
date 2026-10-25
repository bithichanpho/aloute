package com.aloute.persistence.postgres.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Compiles a constrained criteria tree to parameterized PostgreSQL JSONB expressions. */
final class SqlCriteriaCompiler {
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private final ObjectMapper mapper;
    private final Class<?> modelType;

    SqlCriteriaCompiler(ObjectMapper mapper, Class<?> modelType) {
        this.mapper = mapper;
        this.modelType = modelType;
    }

    String compile(Criteria criteria, List<Object> parameters) {
        if (criteria == null || criteria.nodes().isEmpty()) return "TRUE";
        return compileNodes(criteria.nodes(), true, parameters);
    }

    String sortExpression(String field, String alias) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("Sort field is required");
        if (field.equals("_id") || field.equals("id")) return alias + ".document_id";
        if (!SAFE_FIELD.matcher(field).matches()) throw new IllegalArgumentException("Unsupported field: " + field);
        Class<?> type = fieldType(field);
        String json = alias + ".payload ->> '" + field + "'";
        if (type != null && (Number.class.isAssignableFrom(box(type)) || isNumericPrimitive(type))) {
            return "NULLIF(" + json + ", '')::numeric";
        }
        if (type == LocalDateTime.class) return "aloute_jsonb_ts(" + json + ")";
        if (type == Instant.class) return "aloute_jsonb_tstz(" + json + ")";
        if (type == Boolean.class || type == boolean.class) return "NULLIF(" + json + ", '')::boolean";
        return json;
    }

    private String compileNodes(List<Criteria.Node> nodes, boolean and, List<Object> parameters) {
        if (nodes == null || nodes.isEmpty()) return "TRUE";
        List<String> expressions = new ArrayList<>();
        for (Criteria.Node node : nodes) {
            if (node instanceof Criteria.LogicalNode logical) {
                expressions.add("(" + compileNodes(logical.children(), logical.and(), parameters) + ")");
            } else if (node instanceof Criteria.ComparisonNode comparison) {
                expressions.add(compileComparison(comparison, parameters));
            } else if (node instanceof Criteria.TextNode text) {
                String safe = safeField(text.field());
                expressions.add("to_tsvector('simple', COALESCE(payload ->> '" + safe + "', '')) @@ plainto_tsquery('simple', ?)");
                parameters.add(text.value());
            }
        }
        if (expressions.isEmpty()) return "TRUE";
        return String.join(and ? " AND " : " OR ", expressions);
    }

    private String compileComparison(Criteria.ComparisonNode c, List<Object> parameters) {
        // Media history is a JSONB array of revision objects rather than a scalar property.
        // This is currently used for exact URL reference checks during lifecycle cleanup.
        if ("editHistory.imageUrl".equals(c.field()) || "editHistory.videoUrl".equals(c.field())) {
            if (c.operator() != Criteria.Operator.EQ || c.value() == null) {
                throw new IllegalArgumentException("Only non-null equality is supported for " + c.field());
            }
            String nestedField = c.field().substring("editHistory.".length());
            parameters.add(c.value().toString());
            return "EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(payload -> 'editHistory') = 'array' THEN payload -> 'editHistory' ELSE '[]'::jsonb END) AS revision(item) "
                    + "WHERE revision.item ->> '" + nestedField + "' = ?)";
        }
        String field = safeField(c.field());
        boolean idField = "id".equals(field) || "_id".equals(field);
        String keyExists = idField ? "TRUE" : "jsonb_exists(payload, '" + field + "')";
        String json = idField ? "document_id" : "payload ->> '" + field + "'";
        String jsonRaw = idField ? "to_jsonb(document_id)" : "payload -> '" + field + "'";
        Class<?> type = idField ? String.class : fieldType(field);
        boolean arrayField = type != null && (Collection.class.isAssignableFrom(type) || type.isArray());
        boolean mapField = type != null && Map.class.isAssignableFrom(type);

        return switch (c.operator()) {
            case EXISTS -> Boolean.TRUE.equals(c.value()) ? keyExists : (idField ? "FALSE" : "NOT " + keyExists);
            case REGEX -> {
                parameters.add(c.value() == null ? "" : c.value().toString());
                yield "COALESCE(" + json + ", '') ~ ?";
            }
            case FULL_TEXT -> throw new IllegalStateException("Use TextNode for full-text search");
            case EQ -> equality(field, json, jsonRaw, keyExists, type, arrayField, mapField, c.value(), parameters);
            case NE -> inequality(field, json, jsonRaw, keyExists, type, arrayField, mapField, c.value(), parameters);
            case IN -> in(field, json, jsonRaw, keyExists, type, arrayField, mapField, values(c.value()), false, parameters);
            case NIN -> in(field, json, jsonRaw, keyExists, type, arrayField, mapField, values(c.value()), true, parameters);
            case GT, GTE, LT, LTE -> {
                String operator = switch (c.operator()) { case GT -> ">"; case GTE -> ">="; case LT -> "<"; default -> "<="; };
                String expr = comparisonExpression(json, type);
                parameters.add(normalizeValue(c.value(), type));
                yield expr + " " + operator + " " + castPlaceholder(type);
            }
        };
    }

    private String equality(String field, String json, String jsonRaw, String keyExists, Class<?> type,
                            boolean arrayField, boolean mapField, Object value, List<Object> params) {
        if (value == null) return "(NOT " + keyExists + " OR " + ("document_id".equals(json) ? json + " IS NULL" : jsonRaw + " = 'null'::jsonb") + ")";
        if (arrayField) {
            params.add(json(value));
            return "COALESCE(payload -> '" + field + "', '[]'::jsonb) @> jsonb_build_array(?::jsonb)";
        }
        if (mapField) {
            params.add(json(value));
            return jsonRaw + " = ?::jsonb";
        }
        params.add(normalizeValue(value, type));
        return comparisonExpression(json, type) + " = " + castPlaceholder(type);
    }

    private String inequality(String field, String json, String jsonRaw, String keyExists, Class<?> type,
                              boolean arrayField, boolean mapField, Object value, List<Object> params) {
        if (arrayField && value != null) {
            params.add(json(value));
            return "(NOT " + keyExists + " OR NOT (COALESCE(payload -> '" + field + "', '[]'::jsonb) @> jsonb_build_array(?::jsonb)))";
        }
        if (mapField && value != null) {
            params.add(json(value));
            return "(NOT " + keyExists + " OR " + jsonRaw + " <> ?::jsonb)";
        }
        if (value == null) return "(" + keyExists + " AND " + jsonRaw + " <> 'null'::jsonb)";
        params.add(normalizeValue(value, type));
        return "(NOT " + keyExists + " OR " + jsonRaw + " = 'null'::jsonb OR "
                + comparisonExpression(json, type) + " <> " + castPlaceholder(type) + ")";
    }

    private String in(String field, String json, String jsonRaw, String keyExists, Class<?> type,
                      boolean arrayField, boolean mapField, List<?> values, boolean negate, List<Object> params) {
        if (values.isEmpty()) return negate ? "TRUE" : "FALSE";
        if (arrayField) {
            List<String> contains = new ArrayList<>();
            for (Object value : values) {
                if (value == null) contains.add("(" + jsonRaw + " IS NULL OR " + jsonRaw + " = 'null'::jsonb)");
                else {
                    contains.add("COALESCE(payload -> '" + field + "', '[]'::jsonb) @> jsonb_build_array(?::jsonb)");
                    params.add(json(value));
                }
            }
            String any = "(" + String.join(" OR ", contains) + ")";
            return negate ? "(NOT " + any + ")" : any;
        }
        if (mapField) {
            List<String> contains = new ArrayList<>();
            for (Object value : values) {
                if (value == null) contains.add(jsonRaw + " IS NULL");
                else { contains.add(jsonRaw + " = ?::jsonb"); params.add(json(value)); }
            }
            String any = "(" + String.join(" OR ", contains) + ")";
            return negate ? "(NOT " + any + ")" : any;
        }
        List<Object> nonNull = new ArrayList<>();
        boolean hasNull = false;
        for (Object value : values) {
            if (value == null) hasNull = true;
            else nonNull.add(normalizeValue(value, type));
        }
        if (negate) {
            if (nonNull.isEmpty()) {
                if (!hasNull) return "TRUE";
                return "(NOT " + keyExists + " OR (" + keyExists + " AND " + jsonRaw + " <> 'null'::jsonb))";
            }
            String notIn = comparisonExpression(json, type) + " NOT IN ("
                    + String.join(",", java.util.Collections.nCopies(nonNull.size(), castPlaceholder(type))) + ")";
            params.addAll(nonNull);
            if (hasNull) {
                return "(NOT " + keyExists + " OR (" + keyExists + " AND " + jsonRaw
                        + " <> 'null'::jsonb AND " + notIn + "))";
            }
            return "(NOT " + keyExists + " OR " + jsonRaw + " = 'null'::jsonb OR " + notIn + ")";
        }

        List<String> arms = new ArrayList<>();
        if (!nonNull.isEmpty()) {
            arms.add(comparisonExpression(json, type) + " IN ("
                    + String.join(",", java.util.Collections.nCopies(nonNull.size(), castPlaceholder(type))) + ")");
            params.addAll(nonNull);
        }
        if (hasNull) arms.add("(NOT " + keyExists + " OR " + jsonRaw + " = 'null'::jsonb)");
        if (arms.isEmpty()) return "FALSE";
        return "(" + String.join(" OR ", arms) + ")";
    }

    // Date fields go through the IMMUTABLE aloute_jsonb_ts/tstz functions (V20261104) so that
    // sorts and range filters match the expression indexes; a plain ::timestamp cast cannot be indexed.
    private String comparisonExpression(String json, Class<?> type) {
        if (type == null) return json;
        if (Number.class.isAssignableFrom(box(type)) || isNumericPrimitive(type)) return "NULLIF(" + json + ", '')::numeric";
        if (type == Boolean.class || type == boolean.class) return "NULLIF(" + json + ", '')::boolean";
        if (type == LocalDateTime.class) return "aloute_jsonb_ts(" + json + ")";
        if (type == Instant.class) return "aloute_jsonb_tstz(" + json + ")";
        return json;
    }

    private String castPlaceholder(Class<?> type) {
        if (type == null) return "?";
        if (Number.class.isAssignableFrom(box(type)) || isNumericPrimitive(type)) return "CAST(? AS numeric)";
        if (type == Boolean.class || type == boolean.class) return "CAST(? AS boolean)";
        if (type == LocalDateTime.class) return "CAST(? AS timestamp)";
        if (type == Instant.class) return "CAST(? AS timestamptz)";
        return "?";
    }

    private Object normalizeValue(Object value, Class<?> fieldType) {
        if (value == null) return null;
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof LocalDateTime d) return d.toString();
        if (value instanceof Instant instant) return instant.toString();
        if (fieldType != null && fieldType.isEnum() && value instanceof String s) return s;
        return value.toString();
    }

    private List<?> values(Object raw) {
        if (raw instanceof Collection<?> values) return new ArrayList<>(values);
        if (raw instanceof Object[] values) return java.util.Arrays.asList(values);
        return List.of(raw);
    }

    private String safeField(String field) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
        if (field.equals("_id") || field.equals("id")) return field;
        if (!SAFE_FIELD.matcher(field).matches()) throw new IllegalArgumentException("Unsupported field: " + field);
        return field;
    }

    private Class<?> fieldType(String name) {
        String field = name.equals("_id") ? "id" : name;
        Class<?> current = modelType;
        while (current != null && current != Object.class) {
            try { Field f = current.getDeclaredField(field); return f.getType(); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Unable to serialize query value", e); }
    }

    private static boolean isNumericPrimitive(Class<?> type) {
        return type == byte.class || type == short.class || type == int.class || type == long.class
                || type == float.class || type == double.class;
    }

    private static Class<?> box(Class<?> type) {
        if (type == null || !type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
