package com.aloute.common.util;

/** Builds safe SQL LIKE patterns from user input. */
public final class LikePatternUtil {

    /** Escape character to pair with {@code ESCAPE '!'} in the query. */
    public static final char ESCAPE = '!';

    private LikePatternUtil() {}

    /**
     * Returns {@code %term%} with LIKE wildcards in {@code term} escaped, so a search for
     * {@code a_b} or {@code 50%} matches those characters literally instead of acting as wildcards.
     * Use with {@code ... LIKE :pattern ESCAPE '!'}.
     */
    public static String contains(String term) {
        String value = term == null ? "" : term.trim();
        StringBuilder pattern = new StringBuilder(value.length() + 4).append('%');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') pattern.append(ESCAPE);
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
