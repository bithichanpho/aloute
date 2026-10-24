package com.aloute.persistence.postgres.document;

public final class DeleteResult {
    private final long deletedCount;
    public DeleteResult(long deletedCount) { this.deletedCount = deletedCount; }
    public long getDeletedCount() { return deletedCount; }
}
