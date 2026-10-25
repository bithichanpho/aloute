package com.aloute.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.dto.request.RegisterRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

class RegistrationBusinessValidationTest {
    record Case(String id, String field, String value, boolean valid) {
        @Override public String toString() { return id; } // Never log even synthetic password values.
    }

    static Stream<Case> cases() throws Exception {
        try (var input = RegistrationBusinessValidationTest.class.getResourceAsStream("/registration-validation-cases.json")) {
            var json = JsonMapper.builder().build().readTree(input);
            var cases = new java.util.ArrayList<Case>();
            for (var node : json) {
                String value = node.has("value") ? node.get("value").asString()
                        : node.path("prefix").asString("") + node.path("repeat").asString().repeat(node.path("times").asInt()) + node.path("suffix").asString("");
                cases.add(new Case(node.get("id").asString(), node.get("field").asString(), value, node.get("valid").asBoolean()));
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("cases")
    void registrationConstraintsMatchBusinessCases(Case fixture) {
        var request = new RegisterRequest();
        switch (fixture.field()) {
            case "password" -> request.setPassword(fixture.value());
            case "username" -> request.setUsername(fixture.value());
            case "email" -> request.setEmail(fixture.value());
            default -> throw new IllegalArgumentException("Unknown fixture field");
        }
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertEquals(fixture.valid(), factory.getValidator().validateProperty(request, fixture.field()).isEmpty(), fixture.id());
        }
    }

    @Test
    void bcryptByteLimitMustBeRejectedByValidationBeforeTheEncoderThrows() {
        String value = "Aa1!" + "ấ".repeat(24); // Disposable test data, not an account password.
        assertTrue(value.length() < 72);
        assertTrue(value.getBytes(StandardCharsets.UTF_8).length > 72);
        assertThrows(IllegalArgumentException.class, () -> new BCryptPasswordEncoder().encode(value));
        assertFalse(new StrongPasswordValidator().isValid(value, null), "Validation must stop this before registration/reset reaches BCrypt");
    }
}
