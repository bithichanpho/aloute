package com.aloute.dto.response;

import java.time.LocalDateTime;

public record CommunityMemberResponse(
        Long userId,
        String username,
        String fullName,
        String avatarUrl,
        String role,
        LocalDateTime joinedAt) {}
