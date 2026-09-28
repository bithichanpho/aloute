package com.aloute.common.util;

import java.time.Duration;
import java.time.LocalDateTime;

public class TimeAgoUtil {

    public static String format(LocalDateTime pastTime) {
        if (pastTime == null) {
            return "";
        }

        LocalDateTime now = LocalDateTime.now();
        Duration duration = Duration.between(pastTime, now);

        long seconds = duration.getSeconds();

        if (seconds < 60) {
            return "vừa xong";
        }

        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + " phút trước";
        }

        long hours = duration.toHours();
        if (hours < 24) {
            return hours + " giờ trước";
        }

        long days = duration.toDays();
        if (days < 30) {
            return days + " ngày trước";
        }

        long months = days / 30;
        if (months < 12) {
            return months + " tháng trước";
        }

        long years = days / 365;
        return years + " năm trước";
    }
}
