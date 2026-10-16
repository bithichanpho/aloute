package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/firebase")
public class FirebaseConfigController {

    @Value("${app.firebase.web.api-key:}") private String apiKey;
    @Value("${app.firebase.web.auth-domain:}") private String authDomain;
    @Value("${app.firebase.web.project-id:}") private String projectId;
    @Value("${app.firebase.web.storage-bucket:}") private String storageBucket;
    @Value("${app.firebase.web.messaging-sender-id:}") private String senderId;
    @Value("${app.firebase.web.app-id:}") private String appId;
    @Value("${app.firebase.web.vapid-key:}") private String vapidKey;

    private final com.aloute.service.FirebaseAdminService firebaseAdminService;

    public FirebaseConfigController(com.aloute.service.FirebaseAdminService firebaseAdminService) {
        this.firebaseAdminService = firebaseAdminService;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getPublicConfig() {
        boolean webEnabled = present(apiKey) && present(authDomain) && present(projectId)
                && present(senderId) && present(appId);
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("apiKey", clean(apiKey));
        web.put("authDomain", clean(authDomain));
        web.put("projectId", clean(projectId));
        web.put("storageBucket", clean(storageBucket));
        web.put("messagingSenderId", clean(senderId));
        web.put("appId", clean(appId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("webEnabled", webEnabled);
        result.put("googleLoginEnabled", webEnabled && firebaseAdminService.isAuthEnabled());
        result.put(
                "messagingEnabled",
                webEnabled && present(vapidKey) && firebaseAdminService.isMessagingEnabled());
        result.put("web", web);
        result.put("vapidKey", clean(vapidKey));
        return ApiResponse.success(result);
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private String clean(String value) { return value == null ? "" : value.trim(); }
}
