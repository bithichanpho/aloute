package com.aloute.persistence.postgres.document;

public final class UpdateResult {
    private final long matchedCount;
    private final long modifiedCount;
    public UpdateResult(long count) { this(count, count); }
    public UpdateResult(long matchedCount, long modifiedCount) {
        this.matchedCount = matchedCount;
        this.modifiedCount = modifiedCount;
    }
    public long getMatchedCount() { return matchedCount; }
    public long getModifiedCount() { return modifiedCount; }
}
