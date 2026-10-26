package com.aloute.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LikePatternUtilTest {

    @Test
    void wrapsPlainTermsInWildcards() {
        assertEquals("%nguyen%", LikePatternUtil.contains("nguyen"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("%an%", LikePatternUtil.contains("  an "));
    }

    @Test
    void escapesLikeWildcardsSoTheyMatchLiterally() {
        assertEquals("%a!_b%", LikePatternUtil.contains("a_b"));
        assertEquals("%50!%%", LikePatternUtil.contains("50%"));
    }

    @Test
    void escapesTheEscapeCharacterItself() {
        assertEquals("%hi!!%", LikePatternUtil.contains("hi!"));
    }

    @Test
    void nullBehavesLikeEmpty() {
        assertEquals("%%", LikePatternUtil.contains(null));
    }
}
