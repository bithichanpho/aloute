package com.aloute.service;

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class FirebaseAdminService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminService.class);
    private static final String APP_NAME = "aloute-firebase";

    @Value("${app.firebase.admin.project-id:}")
    private String projectId;

    @Value("${app.firebase.admin.credentials-path:}")
    private String credentialsPath;

    private volatile FirebaseAuth firebaseAuth;
    private volatile FirebaseMessaging firebaseMessaging;

    @PostConstruct
    void initialize() {
        if (projectId == null || projectId.isBlank()) {
            log.info("Firebase Admin is disabled: FIREBASE_PROJECT_ID is not configured");
            return;
        }
        try {
            FirebaseApp app = findOrCreateApp();
            firebaseAuth = FirebaseAuth.getInstance(app);
            firebaseMessaging = FirebaseMessaging.getInstance(app);
            log.info("Firebase Admin initialized for project {}", projectId);
        } catch (Exception ex) {
            firebaseAuth = null;
            firebaseMessaging = null;
            log.warn(
                    "Firebase Admin is disabled. Check FIREBASE_PROJECT_ID and service-account credentials ({}).",
                    ex.getClass().getSimpleName());
        }
    }

    private FirebaseApp findOrCreateApp() throws Exception {
        for (FirebaseApp app : FirebaseApp.getApps()) {
            if (APP_NAME.equals(app.getName())) return app;
        }

        GoogleCredentials credentials;
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try (InputStream input = Files.newInputStream(Path.of(credentialsPath))) {
                credentials = GoogleCredentials.fromStream(input);
            }
        } else {
            // Google Cloud ADC / GOOGLE_APPLICATION_CREDENTIALS; never embed a private key.
            credentials = GoogleCredentials.getApplicationDefault();
        }

        FirebaseOptions options =
                FirebaseOptions.builder().setCredentials(credentials).setProjectId(projectId).build();
        return FirebaseApp.initializeApp(options, APP_NAME);
    }

    public boolean isAuthEnabled() {
        return firebaseAuth != null;
    }

    public boolean isMessagingEnabled() {
        return firebaseMessaging != null;
    }

    public FirebaseToken verifyIdToken(String idToken) throws FirebaseAuthException {
        FirebaseAuth auth = firebaseAuth;
        if (auth == null) {
            throw new IllegalStateException(
                    "Firebase server is not configured. Set FIREBASE_PROJECT_ID and server credentials.");
        }
        return auth.verifyIdToken(idToken, true);
    }

    /**
     * Verifies a recent Google sign-in for a sensitive account action. The Firebase ID token must
     * belong to the same verified email as the local account and have a recent auth_time.
     */
    public boolean verifyRecentGoogleReauthentication(String idToken, String expectedEmail) {
        if (idToken == null || idToken.isBlank() || expectedEmail == null || expectedEmail.isBlank()) {
            return false;
        }
        try {
            FirebaseToken token = verifyIdToken(idToken.trim());
            var claims = token.getClaims();
            Object providerClaim = claims.get("firebase");
            String provider = null;
            if (providerClaim instanceof java.util.Map<?, ?> providerInfo) {
                Object signInProvider = providerInfo.get("sign_in_provider");
                if (signInProvider != null) provider = signInProvider.toString();
            }
            if (!"google.com".equals(provider)
                    || !Boolean.TRUE.equals(claims.get("email_verified"))) {
                return false;
            }
            Object emailClaim = claims.get("email");
            if (emailClaim == null
                    || !expectedEmail.trim().equalsIgnoreCase(emailClaim.toString())) {
                return false;
            }
            Object authTimeClaim = claims.get("auth_time");
            if (!(authTimeClaim instanceof Number authTimeNumber)) return false;
            long authTime = authTimeNumber.longValue();
            long ageSeconds = java.time.Instant.now().getEpochSecond() - authTime;
            return ageSeconds >= -60 && ageSeconds <= 600;
        } catch (FirebaseAuthException | RuntimeException ex) {
            // Do not expose provider verification details to the client.
            return false;
        }
    }

    public ApiFuture<String> sendDataMessage(
            String fid, String title, String body, String relativeUrl) {
        return sendDataMessage(fid, title, body, relativeUrl, "NOTIFICATION");
    }

    public ApiFuture<String> sendDataMessage(
            String fid, String title, String body, String relativeUrl, String type) {
        FirebaseMessaging messaging = firebaseMessaging;
        if (messaging == null) {
            throw new IllegalStateException("Firebase Cloud Messaging is not configured");
        }
        Message message =
                Message.builder()
                        .setFid(fid)
                        .putData("title", safe(title, "ALOUTE"))
                        .putData("body", safe(body, "Bạn có thông báo mới"))
                        .putData("url", safeRelativeUrl(relativeUrl))
                        .putData("type", safe(type, "NOTIFICATION"))
                        .build();
        return messaging.sendAsync(message);
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private String safeRelativeUrl(String value) {
        if (value == null || value.isBlank() || !value.startsWith("/") || value.startsWith("//")) {
            return "/activity";
        }
        return value.length() > 250 ? "/activity" : value;
    }
}
