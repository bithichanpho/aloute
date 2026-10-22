package com.aloute.persistence.postgres.document;

/** PostgreSQL full-text search predicate, backed by to_tsvector/plainto_tsquery. */
public final class TextCriteria extends Criteria {
    private String language = "simple";

    private TextCriteria() { }

    public static TextCriteria forDefaultLanguage() { return new TextCriteria(); }
    public static TextCriteria forLanguage(String language) {
        TextCriteria criteria = new TextCriteria();
        // Use PostgreSQL's portable built-in dictionary rather than depending on OS locales.
        criteria.language = "simple";
        return criteria;
    }

    public TextCriteria matching(String phrase) {
        if (phrase == null || phrase.isBlank()) throw new IllegalArgumentException("search phrase is required");
        fullText("content", phrase.trim());
        return this;
    }

    public String language() { return language; }
}
