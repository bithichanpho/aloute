package com.aloute.communities.domain;

import java.util.List;

/** Includes legacy ADMIN membership semantics, which remain equivalent to an owner/manager. */
public final class CommunityRoles {
    private CommunityRoles() {}
    public static List<String> managerRoles() { return List.of("OWNER", "MANAGER", "ADMIN"); }
    public static boolean canManage(String role) { return role != null && managerRoles().contains(role); }
}
