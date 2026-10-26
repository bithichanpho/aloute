package com.aloute.service;

import java.util.List;

/** Source-compatible facade for the Posts-owned tag parser. */
public final class ContentTagService {
    private ContentTagService() {}
    public static List<String> extract(String content, char prefix) {
        return com.aloute.posts.domain.ContentTags.extract(content, prefix);
    }
}
