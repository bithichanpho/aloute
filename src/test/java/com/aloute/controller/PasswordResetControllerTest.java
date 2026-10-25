package com.aloute.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.aloute.service.*;
import com.aloute.security.ClientIpResolver;
import com.aloute.common.exception.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class PasswordResetControllerTest {
    PasswordResetFlowService flow=mock(PasswordResetFlowService.class);
    AuthRateLimitService limits=mock(AuthRateLimitService.class);
    ClientIpResolver ip=mock(ClientIpResolver.class);
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new PasswordResetController(flow,limits,ip))
            .setControllerAdvice(new GlobalExceptionHandler(JsonMapper.builder().build())).build();when(ip.resolve(any())).thenReturn("127.0.0.1");}
    @Test void directPasswordPageRequiresVerifiedServerProof() throws Exception {
        when(flow.verifiedEmail(null)).thenReturn(Optional.empty());
        mvc.perform(get("/reset-password").param("email","victim@example.invalid")).andExpect(redirectedUrl("/verify-reset-code"));
        when(flow.verifiedEmail("proof")).thenReturn(Optional.of("owner@example.invalid"));
        mvc.perform(get("/reset-password").cookie(new jakarta.servlet.http.Cookie(PasswordResetController.COOKIE,"proof")))
                .andExpect(view().name("auth/reset-password")).andExpect(model().attribute("email","owner@example.invalid"));
    }
    @Test void verifyingOtpSetsOnlyHttpOnlyProofCookieAndNoJsonCredential() throws Exception {
        when(flow.verify("owner@example.invalid","012345")).thenReturn("encrypted-proof");
        mvc.perform(post("/api/auth/verify-reset-code").contentType("application/json").content("{\"email\":\"Owner@example.invalid\",\"code\":\"012345\"}"))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly(PasswordResetController.COOKIE,true))
                .andExpect(cookie().maxAge(PasswordResetController.COOKIE,300)).andExpect(cookie().sameSite(PasswordResetController.COOKIE,"Strict"))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(limits).reset("RESET_OTP_TARGET","owner@example.invalid");
    }
    @Test void malformedCodeAndWeakPasswordAreRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/verify-reset-code").contentType("application/json").content("{\"email\":\"a@example.invalid\",\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/complete-password-reset").contentType("application/json").content("{\"newPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(flow);
    }
    @Test void completedResetExpiresProofCookie() throws Exception {
        mvc.perform(post("/api/auth/complete-password-reset").cookie(new jakarta.servlet.http.Cookie(PasswordResetController.COOKIE,"proof"))
                .contentType("application/json").content("{\"newPassword\":\"New-Pass1!\"}"))
                .andExpect(status().isOk()).andExpect(cookie().maxAge(PasswordResetController.COOKIE,0));
        verify(flow).complete("proof","New-Pass1!");
    }
}
