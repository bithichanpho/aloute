package com.aloute.common.util;

import java.util.Set;

public final class ReactionType {

    public static final String LIKE = "LIKE";
    public static final String LOVE = "LOVE";
    public static final String HAHA = "HAHA";
    public static final String WOW = "WOW";
    public static final String SAD = "SAD";
    public static final String ANGRY = "ANGRY";

    private static final Set<String> ALL = Set.of(LIKE, LOVE, HAHA, WOW, SAD, ANGRY);

    private ReactionType() {}

    public static boolean isValid(String type) {
        return type != null && ALL.contains(type.toUpperCase());
    }

    public static String normalize(String type) {
        if (type == null) return LIKE;
        String upper = type.trim().toUpperCase();
        return ALL.contains(upper) ? upper : LIKE;
    }
}
