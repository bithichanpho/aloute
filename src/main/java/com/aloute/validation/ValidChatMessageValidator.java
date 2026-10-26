package com.aloute.validation;

import com.aloute.dto.request.SendMessageRequest;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidChatMessageValidator
        implements ConstraintValidator<ValidChatMessage, SendMessageRequest> {
    @Override
    public boolean isValid(SendMessageRequest request, ConstraintValidatorContext context) {
        if (request == null) return false;
        boolean direct = request.getReceiverId() != null;
        boolean group = request.getGroupId() != null;
        boolean content = request.getContent() != null && !request.getContent().isBlank();
        boolean media = request.getMediaUrl() != null && !request.getMediaUrl().isBlank();
        return direct != group && (content || media);
    }
}
