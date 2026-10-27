package com.aloute;

import static org.junit.jupiter.api.Assertions.*;
import com.aloute.dto.response.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ProfileLinkTemplateTest {
    @Test void feedAuthorsQuotesAndRepostersUseHandlesInRealServerHtml() throws Exception {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext(); var request = new MockHttpServletRequest(servlet); request.setRequestURI("/feed");
        var context = new WebContext(JakartaServletWebApplication.buildApplication(servlet).buildExchange(request, new MockHttpServletResponse()), Locale.forLanguageTag("vi"));
        var viewer = new UserProfileDto(); viewer.setId(1L); viewer.setUsername("viewer"); context.setVariable("currentUser", viewer);
        var post = new PostResponse(); post.setId("quote"); post.setUserId(26L); post.setUsername("caso"); post.setContent("Quote @alice.name");
        var original = new PostResponse.RepostOrigin(); original.setPostId("origin"); original.setUserId(27L); original.setUsername("alice.name"); original.setContent("Original"); post.setRepostOf(original);
        var actor = new PostResponse.RepostActor(); actor.setUserId(28L); actor.setUsername("legacy@example.com"); post.setRecentReposters(List.of(actor));
        context.setVariable("posts", List.of(post));
        String html = engine.process("feed/index", Set.of(".post"), context);
        for (String path : List.of("/@caso", "/@alice.name", "/@legacy%40example.com")) assertTrue(html.contains("href=\"" + path + "\""), path);
        assertFalse(html.contains("href=\"/profile/26\""));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/username-profile-feed-fragment.html"), html);
    }
}
