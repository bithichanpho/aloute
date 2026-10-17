package com.aloute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailDispatcher.class);
    private final EmailService email;
    public PasswordResetMailDispatcher(EmailService email) { this.email = email; }
    @Async("passwordMailExecutor")
    public void send(String recipient, String fullName, String token) {
        try { email.sendResetPasswordEmail(recipient, fullName, token); }
        catch (RuntimeException ex) { log.error("Password reset email delivery failed ({})", ex.getClass().getSimpleName(), ex); }
    }
}
