package com.aloute.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class UsernamePolicyTest {

    // Same expression as RegisterRequest#username.
    private static final Pattern REGISTRATION =
            Pattern.compile("^[A-Za-z0-9_](?:[A-Za-z0-9_.-]{1,48}[A-Za-z0-9_])?$");

    @Test
    void deletedPlaceholderIsNeverRegistrableAndFitsTheColumn() {
        for (long id : new long[] {1L, 123L, Long.MAX_VALUE}) {
            String placeholder = UsernamePolicy.deletedAccountUsername(id);
            assertFalse(
                    REGISTRATION.matcher(placeholder).matches(),
                    placeholder + " must not match the registration pattern");
            assertTrue(placeholder.length() <= 50, "users.username is limited to 50 characters");
        }
    }

    @Test
    void placeholderIsUniquePerUser() {
        assertNotEquals(
                UsernamePolicy.deletedAccountUsername(1L), UsernamePolicy.deletedAccountUsername(2L));
    }

    @Test
    void reservedNamesAreDetectedCaseInsensitively() {
        assertTrue(UsernamePolicy.isReserved("deleted_123"));
        assertTrue(UsernamePolicy.isReserved("  DELETED_123 "));
        assertTrue(UsernamePolicy.isReserved("deleted~5"));
        assertFalse(UsernamePolicy.isReserved("deleted"));
        assertFalse(UsernamePolicy.isReserved("my_deleted_1"));
        assertFalse(UsernamePolicy.isReserved(null));
    }

    @Test
    void placeholderRequiresAUserId() {
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.deletedAccountUsername(null));
    }
}
