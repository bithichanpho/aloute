package com.aloute.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aloute.dto.request.ReactionRequest;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.request.SendMessageRequest;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.request.SharePostRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

class RequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void passwordRequiresLengthAndCharacterGroups() {
        RegisterRequest request = validRegistration();
        request.setPassword("weakpass");
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setPassword("Strong#123");
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void usernameRejectsEmailSymbolsAndAcceptsSafeSeparators() {
        RegisterRequest invalid = validRegistration();
        invalid.setUsername("quyvy@@@");
        assertFalse(VALIDATOR.validate(invalid).isEmpty());

        RegisterRequest valid = validRegistration();
        valid.setUsername("quy.vy_01");
        assertTrue(VALIDATOR.validate(valid).isEmpty());
    }

    @Test
    void usernameCannotUseDeletedAccountPrefix() {
        for (String reserved : new String[] {"deleted_123", "DELETED_123", "Deleted_x", "deleted_"}) {
            RegisterRequest request = validRegistration();
            request.setUsername(reserved);
            assertFalse(VALIDATOR.validate(request).isEmpty(), reserved + " must be rejected");
        }

        for (String allowed : new String[] {"deleted", "deletedfan", "deleted.1", "my_deleted_1"}) {
            RegisterRequest request = validRegistration();
            request.setUsername(allowed);
            assertTrue(VALIDATOR.validate(request).isEmpty(), allowed + " must stay allowed");
        }
    }

    @Test
    void reactionOnlyAcceptsSupportedTypes() {
        ReactionRequest request = new ReactionRequest();
        request.setType("surprised");
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setType("LOVE");
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void chatRequiresOneDestinationAndContentOrMedia() {
        SendMessageRequest request = new SendMessageRequest();
        request.setReceiverId(2L);
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setContent("Xin chào");
        assertTrue(VALIDATOR.validate(request).isEmpty());

        request.setGroupId(3L);
        assertFalse(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void mediaUrlOnlyAllowsHttpAndHttps() {
        SendMessageRequest request = new SendMessageRequest();
        request.setReceiverId(2L);
        request.setMediaUrl("javascript:alert(1)");
        request.setMediaType("image");
        request.setMediaSize(10L);
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setMediaUrl("https://cdn.example.com/image.png");
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void postAcceptsMediaWithoutTextButRejectsAnEmptyPayload() {
        CreatePostRequest request = new CreatePostRequest();
        assertFalse(VALIDATOR.validate(request).isEmpty());

        request.setImageUrl("https://cdn.example.com/post.png");
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void sharingRequiresRecipientsAndLimitsRecipientCountAndNoteLength() {
        SharePostRequest valid = new SharePostRequest(List.of(2L, 3L), "Xem bài này nhé");
        assertTrue(VALIDATOR.validate(valid).isEmpty());

        assertFalse(VALIDATOR.validate(new SharePostRequest(List.of(), "")).isEmpty());
        assertFalse(VALIDATOR.validate(new SharePostRequest(
                LongStream.rangeClosed(2, 22).boxed().toList(), "")).isEmpty());
        assertFalse(VALIDATOR.validate(new SharePostRequest(List.of(2L), "x".repeat(501))).isEmpty());
    }

    private RegisterRequest validRegistration() {
        RegisterRequest request = new RegisterRequest();
        request.setFullName("Nguyễn Văn A");
        request.setUsername("nguyenvana");
        request.setEmail("a@example.com");
        request.setPassword("Strong#123");
        return request;
    }
}
