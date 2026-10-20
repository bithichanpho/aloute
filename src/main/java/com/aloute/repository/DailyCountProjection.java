package com.aloute.repository;

import java.time.LocalDate;

/** Database-side daily aggregation used by the HOST dashboard. */
public interface DailyCountProjection {
    LocalDate getDay();
    long getCount();
}
