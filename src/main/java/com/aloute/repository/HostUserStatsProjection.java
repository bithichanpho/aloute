package com.aloute.repository;

public interface HostUserStatsProjection {
    long getTotalUsers();
    long getNewUsersToday();
    long getLockedAccounts();
}
