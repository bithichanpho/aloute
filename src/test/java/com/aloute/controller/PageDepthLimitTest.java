package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

/**
 * Offset pagination gets slower the deeper it goes, so every user-facing "page" parameter must carry
 * an upper bound. This guards against a new endpoint (or a refactor) silently dropping the bound.
 */
class PageDepthLimitTest {

    @Test
    void chatHistoryPagesAreBounded() {
        assertBounded(ChatController.class, "getMessages", 200);
        assertBounded(ChatController.class, "getGroupMessages", 200);
    }

    @Test
    void postAndProfilePagesAreBounded() {
        assertBounded(PostController.class, "getFeed", 100);
        assertBounded(PostController.class, "getPostsByUser", 100);
        assertBounded(ViewController.class, "profileFragment", 100);
        assertBounded(ViewController.class, "profileRepostFragment", 100);
    }

    @Test
    void communitySavedAndCommentPagesAreBounded() {
        assertBounded(CommunityController.class, "getPosts", 100);
        assertBounded(ViewController.class, "communityFeedFragment", 100);
        assertBounded(SocialFeatureController.class, "saved", 100);
        assertBounded(CommentController.class, "getComments", 100);
    }

    private static void assertBounded(Class<?> controller, String methodName, int maxAllowed) {
        List<Method> candidates = Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .toList();
        assertFalse(candidates.isEmpty(), controller.getSimpleName() + "#" + methodName + " not found");
        for (Method method : candidates) {
            Parameter page = Arrays.stream(method.getParameters())
                    .filter(parameter -> parameter.getType() == int.class)
                    .filter(parameter -> {
                        RequestParam request = parameter.getAnnotation(RequestParam.class);
                        return request != null && "0".equals(request.defaultValue());
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            controller.getSimpleName() + "#" + methodName + " has no page parameter"));
            Max max = page.getAnnotation(Max.class);
            assertNotNull(max, controller.getSimpleName() + "#" + methodName + " page has no @Max");
            assertEquals(maxAllowed, max.value(),
                    controller.getSimpleName() + "#" + methodName + " page limit");
        }
    }
}
