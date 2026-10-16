package com.aloute.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidChatMessageValidator.class)
public @interface ValidChatMessage {
    String message() default "Tin nhắn phải có đúng một người nhận hoặc nhóm nhận và có nội dung hoặc tệp";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
