package com.aloute.persistence.postgres.document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Small SQL-translatable criteria tree for PostgreSQL JSONB records. */
public class Criteria {
    public enum Operator { EQ, NE, IN, NIN, GT, GTE, LT, LTE, EXISTS, REGEX, FULL_TEXT, VALID_DATE_TIME, IN_READ_VIEW }
    public record ReadView(String name, Object viewerId, Object includedValue) {
        public ReadView {
            if (name == null || !name.matches("aloute_read_[a-z_]+"))
                throw new IllegalArgumentException("Only explicitly published read views are supported");
        }
    }
    public sealed interface Node permits LogicalNode, ComparisonNode, TextNode { }
    public record LogicalNode(boolean and, List<Node> children) implements Node { }
    public record ComparisonNode(String field, Operator operator, Object value) implements Node { }
    public record TextNode(String field, String value) implements Node { }

    private final List<Node> nodes = new ArrayList<>();
    private String currentField;

    public Criteria() { }

    private Criteria(String field) { this.currentField = field; }

    public static Criteria where(String field) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
        return new Criteria(field);
    }

    /**
     * Keyset predicate for a descending (field, id) sort: rows strictly before the cursor.
     * The redundant {@code field <= value} bound lets PostgreSQL start the index scan at the
     * cursor; the OR alone forces it to walk every newer row first.
     */
    public static Criteria keysetBefore(String field, Object value, String idField, Object id) {
        return new Criteria().andOperator(
                where(field).lte(value),
                new Criteria().orOperator(
                        where(field).lt(value),
                        new Criteria().andOperator(where(field).is(value), where(idField).lt(id))));
    }

    /** Keyset predicate for an ascending (field, id) sort: rows strictly after the cursor. */
    public static Criteria keysetAfter(String field, Object value, String idField, Object id) {
        return new Criteria().andOperator(
                where(field).gte(value),
                new Criteria().orOperator(
                        where(field).gt(value),
                        new Criteria().andOperator(where(field).is(value), where(idField).gt(id))));
    }

    public Criteria and(String field) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
        this.currentField = field;
        return this;
    }

    public Criteria is(Object value) { add(Operator.EQ, value); return this; }
    public Criteria ne(Object value) { add(Operator.NE, value); return this; }
    public Criteria gt(Object value) { add(Operator.GT, value); return this; }
    public Criteria gte(Object value) { add(Operator.GTE, value); return this; }
    public Criteria lt(Object value) { add(Operator.LT, value); return this; }
    public Criteria lte(Object value) { add(Operator.LTE, value); return this; }
    public Criteria exists(boolean value) { add(Operator.EXISTS, value); return this; }
    public Criteria regex(String pattern) { add(Operator.REGEX, pattern); return this; }
    /** Requires a parseable non-null temporal value without mutating legacy/corrupt payloads. */
    public Criteria validDateTime() { add(Operator.VALID_DATE_TIME, null); return this; }
    /** Named read contract: viewer_id -> value. Values are bound, never client-supplied SQL. */
    public Criteria inReadView(String name, Object viewerId, Object includedValue) {
        add(Operator.IN_READ_VIEW, new ReadView(name, viewerId, includedValue)); return this;
    }

    public Criteria in(Collection<?> values) {
        add(Operator.IN, values == null ? List.of() : new ArrayList<>(values));
        return this;
    }

    public Criteria in(Object... values) {
        add(Operator.IN, values == null ? List.of() : Arrays.asList(values));
        return this;
    }

    public Criteria nin(Collection<?> values) {
        add(Operator.NIN, values == null ? List.of() : new ArrayList<>(values));
        return this;
    }

    public Criteria nin(Object... values) {
        add(Operator.NIN, values == null ? List.of() : Arrays.asList(values));
        return this;
    }

    public Criteria andOperator(Criteria... criteria) {
        nodes.add(new LogicalNode(true, toNodes(criteria)));
        return this;
    }

    public Criteria orOperator(Criteria... criteria) {
        nodes.add(new LogicalNode(false, toNodes(criteria)));
        return this;
    }

    Criteria fullText(String field, String phrase) {
        nodes.add(new TextNode(field, phrase));
        return this;
    }

    public List<Node> nodes() { return List.copyOf(nodes); }

    private void add(Operator operator, Object value) {
        if (currentField == null || currentField.isBlank()) {
            throw new IllegalStateException("Call Criteria.where(field) or and(field) before an operator");
        }
        nodes.add(new ComparisonNode(currentField, operator, value));
    }

    private static List<Node> toNodes(Criteria[] criteria) {
        if (criteria == null || criteria.length == 0) return List.of();
        List<Node> result = new ArrayList<>();
        for (Criteria item : criteria) if (item != null) result.add(new LogicalNode(true, item.nodes()));
        return result;
    }
}
