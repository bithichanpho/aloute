package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class ContentTagServiceTest {
    @Test
    void recognizesVietnameseHashtagsAndDeduplicates() {
        assertEquals(
                List.of("việt_nam", "java"),
                ContentTagService.extract("#Việt_Nam #JAVA #java", '#'));
    }

    @Test
    void excludesEmailAddressesAndFindsMentions() {
        assertEquals(List.of("alice"), ContentTagService.extract("a@example.com chào @Alice", '@'));
    }

    @Test
    void mentionsSupportExistingDottedUsernames() {
        assertEquals(
                List.of("demo.minh", "alice-bob"),
                ContentTagService.extract("@demo.minh và @alice-bob.", '@'));
    }
}
