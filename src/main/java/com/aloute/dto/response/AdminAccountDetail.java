package com.aloute.dto.response;

import java.util.List;

public record AdminAccountDetail(
        AdminAccountSummary account,
        AdminAccountAuditResponse latestAction,
        List<AdminAccountAuditResponse> recentHistory) {}
