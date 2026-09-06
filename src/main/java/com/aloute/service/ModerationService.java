package com.aloute.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.ModerationStatus;

@Service
public class ModerationService {

    private final RestClient restClient;

    public ModerationService(@Value("${app.moderation.service-url:http://localhost:8000}") String serviceUrl) {
        this.restClient = RestClient.builder().baseUrl(serviceUrl).build();
    }

    public ModerationResult check(String content) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/moderate")
                    .body(Map.of("text", content))
                    .retrieve()
                    .body(Map.class);

            String label = (String) response.get("label");
            double score = ((Number) response.get("score")).doubleValue();
            return new ModerationResult(label, score);
        } catch (RestClientException e) {
            // ML Service chưa sẵn sàng — coi như nội dung an toàn tạm thời
            return new ModerationResult("unknown", 0.0);
        }
    }

    public ModerationStatus resolveStatus(double toxicityScore) {
        if (toxicityScore >= 0.8) return ModerationStatus.HIDDEN;
        if (toxicityScore >= 0.5) return ModerationStatus.FLAGGED;
        return ModerationStatus.APPROVED;
    }
}
