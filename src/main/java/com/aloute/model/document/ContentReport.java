package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class ContentReport {
    @org.springframework.data.annotation.Id private String id;
    private Long reporterId;
    private String targetType;
    private String targetId;
    private String reason;
    private String status = "OPEN";
    private Long reviewedBy;
    private String decision;
    private java.time.Instant reviewedAt;
    private java.time.Instant createdAt = java.time.Instant.now();
}
