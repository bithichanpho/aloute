package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

@lombok.Data
@PgDocument(collection = "account_security")
public class AccountSecurity {
    @org.springframework.data.annotation.Id private Long id;
    private String secret;
    private String pendingSecret;
    private java.time.Instant pendingExpiresAt;
    private boolean enabled;
    private long lastStep = -1;
    private java.util.List<String> recoveryHashes = new java.util.ArrayList<>();
}
