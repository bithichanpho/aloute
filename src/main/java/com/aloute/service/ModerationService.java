package com.aloute.service;

import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.ModerationStatus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Service
public class ModerationService {

    private final RestClient restClient;

    public ModerationService(
            @Value("${app.moderation.service-url:http://localhost:8000}") String serviceUrl) {
        this.restClient = RestClient.builder().baseUrl(serviceUrl).build();
    }

    public ModerationResult check(String content) {
        try {
            Map<?, ?> response =
                    restClient
                            .post()
                            .uri("/moderate")
                            .body(Map.of("text", content))
                            .retrieve()
                            .body(Map.class);

            String label = (String) response.get("label");
            double score = ((Number) response.get("score")).doubleValue();
            return new ModerationResult(label, score);
        } catch (RestClientException e) {

            return new ModerationResult("unknown", 0.0);
        }
    }

    public ModerationResult checkMedia(String url, String mediaType) {
        try {
            Map<?, ?> response =
                    restClient
                            .post()
                            .uri("/moderate-media")
                            .body(Map.of("url", url, "mediaType", mediaType))
                            .retrieve()
                            .body(Map.class);
            if (response == null) return new ModerationResult("unknown", 0.0);
            Object scoreValue = response.get("score");
            Object labelValue = response.get("label");
            String label = labelValue == null ? "unknown" : labelValue.toString();
            double score = scoreValue instanceof Number number ? number.doubleValue() : 0.0;
            return new ModerationResult(label, score);
        } catch (RuntimeException exception) {
            return new ModerationResult("unknown", 0.0);
        }
    }

    public boolean isMediaApproved(String url, String mediaType) {
        if (url == null || url.isBlank()) return true;
        ModerationResult result = checkMedia(url, mediaType);
        if ("unknown".equalsIgnoreCase(result.getLabel())) return true;
        return resolveStatus(result.getScore()) == ModerationStatus.APPROVED;
    }

    public ModerationStatus resolveStatus(double toxicityScore) {
        if (toxicityScore >= 0.8) return ModerationStatus.HIDDEN;
        if (toxicityScore >= 0.5) return ModerationStatus.FLAGGED;
        return ModerationStatus.APPROVED;
    }
}
