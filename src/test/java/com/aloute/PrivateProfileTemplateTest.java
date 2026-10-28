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

class PrivateProfileTemplateTest {
    @Test
    void outsidersAndPendingFollowersSeeOneLineNoticeAndNoContentOrLoaders() {
        for(boolean pending:List.of(false,true)) {
            String html=render(true,false,false,pending,null);
            assertTrue(html.contains("Đây là tài khoản riêng tư."));
            assertFalse(html.contains("Theo dõi và chờ được chấp nhận"));
            for(String forbidden:List.of("PRIVATE_CONTENT_MARKER","/private.jpg","data-profile-view=","data-load-posts","id=\"profilePosts\"","id=\"profileRepostsWrap\"","id=\"profileGridWrap\""))
                assertFalse(html.contains(forbidden),forbidden);
        }
    }

    @Test
    void ownersFollowersAndPublicProfilesKeepTheirContentViews() {
        for(String html:List.of(render(true,true,false,false,null),render(true,false,true,false,null),render(false,false,false,false,null))) {
            assertTrue(html.contains("PRIVATE_CONTENT_MARKER"));
            for(String view:List.of("list","grid","reposts")) assertTrue(html.contains("data-profile-view=\""+view+"\""));
            assertFalse(html.contains("Đây là tài khoản riêng tư."));
        }
    }

    @Test
    void requesterProfileShowsDecisionForTheRecipientEvenWhenContentIsPrivate() {
        String html=render(true,false,false,false,"1:2");
        assertTrue(html.contains("Người này đã gửi yêu cầu theo dõi bạn."));
        assertTrue(html.contains("data-follow-request-actions=\"1:2\""));
        assertTrue(html.contains("data-follow-profile=\"true\""));
        assertFalse(render(true,false,false,false,null).contains("data-follow-decision="));
    }

    String render(boolean privateAccount,boolean following,boolean own,boolean pending,String incoming) {
        var resolver=new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet=new MockServletContext(); var request=new MockHttpServletRequest(servlet); request.setRequestURI("/profile/1");
        var exchange=JakartaServletWebApplication.buildApplication(servlet).buildExchange(request,new MockHttpServletResponse());
        var context=new WebContext(exchange,Locale.forLanguageTag("vi"));
        var profile=new UserProfileDto(); profile.setId(1L); profile.setUsername("privateuser"); profile.setFullName("Private User");
        profile.setAvatarUrl("/images/avatar_default.png"); profile.setPrivateAccount(privateAccount); profile.setFollowedByCurrentUser(following);
        profile.setFollowRequestPending(pending); profile.setIncomingFollowRequestId(incoming);
        var viewer=new UserProfileDto(); viewer.setId(own?1L:2L); viewer.setUsername("viewer");
        context.setVariable("currentUser",viewer); context.setVariable("profileUser",profile); context.setVariable("isOwnProfile",own);
        context.setVariable("isSystemAdmin",false); context.setVariable("activeNav","profile"); context.setVariable("unreadCount",0L); context.setVariable("unreadMessageCount",0L);
        var post=new PostResponse(); post.setId("private-post"); post.setUserId(1L); post.setUsername("privateuser"); post.setContent("PRIVATE_CONTENT_MARKER"); post.setImageUrl("/private.jpg");
        for(String key:List.of("userPosts","repostPosts","mediaPosts")) context.setVariable(key,List.of(post));
        context.setVariable("postCount",1L); context.setVariable("profilePostsHasMore",true); context.setVariable("profilePostsNextCursor","next");
        context.setVariable("profileRepostsHasMore",true); context.setVariable("profileRepostsDeferred",true); context.setVariable("profileRepostsNextCursor","next");
        return engine.process("profile/view",context);
    }
}
