package com.aloute;

import static org.junit.jupiter.api.Assertions.*;
import com.aloute.dto.response.*;
import com.aloute.model.document.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class PostMetadataTemplateTest {
    String render(String fragment, Map<String,Object> variables) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/");
        resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var context = new Context(Locale.forLanguageTag("vi"), variables);
        return engine.process("fragments/post-metadata", Set.of(fragment), context);
    }

    @Test void audienceIconsHaveAccessibleNamesAndDistinctShapes() throws Exception {
        var labels = Map.of(PostVisibility.PUBLIC,"Công khai", PostVisibility.FRIENDS,"Bạn bè",PostVisibility.CLOSE_FRIENDS,"Bạn thân",PostVisibility.ONLY_ME,"Chỉ mình tôi");
        var shapes = new HashSet<String>();
        var browserFixture = new StringBuilder();
        for (var entry : labels.entrySet()) {
            String html = render("audience",Map.of("visibility",entry.getKey()));
            assertTrue(html.contains("data-post-audience=\""+entry.getKey()+"\""));
            assertTrue(html.contains("aria-label=\""+entry.getValue()+"\""));
            assertTrue(html.contains("title=\""+entry.getValue()+"\""));
            assertEquals(1,html.split("<svg",-1).length-1);
            shapes.add(html.substring(html.indexOf("<svg"),html.indexOf("</svg>")));
            browserFixture.append(html);
        }
        assertEquals(4, shapes.size());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/post-audience-fragment.html"), browserFixture);
    }

    @Test void onlyOwnerSeesPendingOrFlaggedNoticeAndOutageRetryButton() {
        var post = new PostResponse(); post.setId("post"); post.setUserId(1L);
        var viewer = new UserProfileDto(); viewer.setId(1L);
        var variables = Map.<String,Object>of("post",post,"currentUser",viewer);
        post.setModerationStatus(ModerationStatus.PENDING);
        assertTrue(render("review",variables).contains("Đang kiểm duyệt"));
        assertFalse(render("review",variables).contains("data-moderation-retry"));
        post.setModerationStatus(ModerationStatus.FLAGGED);
        assertTrue(render("review",variables).contains("Chờ xem xét"));
        post.setModerationRetryAvailable(true);
        assertTrue(render("review",variables).contains("mất kết nối"));
        assertTrue(render("review",variables).contains("data-review-post-id=\"post\""));
        viewer.setId(2L);
        assertFalse(render("review",variables).contains("Chỉ bạn nhìn thấy"));
        assertFalse(render("review",variables).contains("data-moderation-retry"));
    }
}
