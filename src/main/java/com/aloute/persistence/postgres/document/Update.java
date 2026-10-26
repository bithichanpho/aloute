package com.aloute.persistence.postgres.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atomic JSONB field mutations expressed as PostgreSQL operations. */
public class Update {
    public enum Kind { SET, UNSET, INC, ADD_TO_SET, PUSH, PULL }
    public record Operation(String field, Kind kind, Object value, Integer slice) { }

    private final List<Operation> operations = new ArrayList<>();

    public Update set(String field, Object value) {
        operations.add(new Operation(field, Kind.SET, value, null)); return this;
    }
    public Update unset(String field) {
        operations.add(new Operation(field, Kind.UNSET, null, null)); return this;
    }
    public Update inc(String field, Number amount) {
        operations.add(new Operation(field, Kind.INC, amount == null ? 0 : amount, null)); return this;
    }
    public Update addToSet(String field, Object value) {
        operations.add(new Operation(field, Kind.ADD_TO_SET, List.of(value), null)); return this;
    }
    public Update pull(String field, Object value) {
        operations.add(new Operation(field, Kind.PULL, value, null)); return this;
    }
    public PushBuilder push(String field) { return new PushBuilder(this, field); }
    /** Returns an immutable view of the queued mutations, useful for inspection and unit tests. */
    public List<Operation> operations() { return List.copyOf(operations); }

    public static final class PushBuilder {
        private final Update update;
        private final String field;
        private Integer slice;
        private PushBuilder(Update update, String field) { this.update = update; this.field = field; }
        public PushBuilder slice(int slice) { this.slice = slice; return this; }
        public Update each(Object... values) {
            List<Object> items = new ArrayList<>();
            if (values != null) java.util.Collections.addAll(items, values);
            update.operations.add(new Operation(field, Kind.PUSH, items, slice));
            return update;
        }
    }
}
