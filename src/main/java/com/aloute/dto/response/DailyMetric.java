package com.aloute.dto.response;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public record DailyMetric(LocalDate date, long count) {
    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("dd/MM");

    public String label() {
        return date.format(LABEL_FORMAT);
    }
}
