package com.aloute.posts.domain;

import java.util.*;
import java.util.regex.Pattern;

public final class ContentTags {
    private ContentTags() {}

    public static List<String> extract(String content, char prefix) {
        if (content == null) return List.of();
        String name =
                prefix == '@'
                        ? "[\\p{L}\\p{N}_](?:[\\p{L}\\p{N}_.-]{0,48}[\\p{L}\\p{N}_])?"
                        : "[\\p{L}\\p{N}_]{1,50}";
        var matcher =
                Pattern.compile("(?<![\\p{L}\\p{N}_])" + prefix + "(" + name + ")")
                        .matcher(content);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find() && result.size() < 30)
            result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }
}
