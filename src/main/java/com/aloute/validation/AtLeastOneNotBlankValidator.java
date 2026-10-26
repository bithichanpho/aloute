package com.aloute.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.reflect.Field;
import java.util.Collection;

public class AtLeastOneNotBlankValidator
        implements ConstraintValidator<AtLeastOneNotBlank, Object> {
    private String[] fields;

    @Override
    public void initialize(AtLeastOneNotBlank annotation) {
        fields = annotation.fields();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return false;
        for (String name : fields) {
            try {
                Field field = value.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object candidate = field.get(value);
                if (candidate instanceof String text && !text.isBlank()) return true;
                if (candidate instanceof Collection<?> items && !items.isEmpty()) return true;
            } catch (ReflectiveOperationException exception) {
                return false;
            }
        }
        return false;
    }
}
