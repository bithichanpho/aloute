package com.aloute.persistence.postgres.document;

/** Return mode for a find-and-update operation. */
public final class FindAndModifyOptions {
    private boolean returnNew;
    private FindAndModifyOptions() { }
    public static FindAndModifyOptions options() { return new FindAndModifyOptions(); }
    public FindAndModifyOptions returnNew(boolean value) { this.returnNew = value; return this; }
    public boolean isReturnNew() { return returnNew; }
}
