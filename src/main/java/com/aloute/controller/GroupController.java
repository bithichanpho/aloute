package com.aloute.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.AddGroupMemberRequest;
import com.aloute.dto.request.CreateGroupRequest;
import com.aloute.dto.response.GroupResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.GroupService;

import jakarta.validation.Valid;

/**
 * Quản lý nhóm chat: tạo nhóm, xem chi tiết/thành viên, thêm/xóa thành viên,
 * rời nhóm. Gửi/đọc TIN NHẮN nhóm vẫn nằm ở ChatController — controller này
 * chỉ lo phần "quản trị nhóm".
 */
@RestController
@RequestMapping("/api/chat/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    public ApiResponse<GroupResponse> createGroup(
            Authentication authentication, @Valid @RequestBody CreateGroupRequest request) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success("Đã tạo nhóm", groupService.createGroup(userId, request));
    }

    @GetMapping("/{groupId}")
    public ApiResponse<GroupResponse> getGroup(Authentication authentication, @PathVariable Long groupId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(groupService.getGroupDetail(groupId, userId));
    }

    @PostMapping("/{groupId}/members")
    public ApiResponse<GroupResponse> addMember(
            Authentication authentication, @PathVariable Long groupId,
            @Valid @RequestBody AddGroupMemberRequest request) {
        Long userId = requireUserId(authentication);
        groupService.addMember(groupId, userId, request.getUserId());
        return ApiResponse.success("Đã thêm thành viên", groupService.getGroupDetail(groupId, userId));
    }

    /** targetUserId == chính mình => rời nhóm; khác => ADMIN đang xóa người khác. */
    @DeleteMapping("/{groupId}/members/{targetUserId}")
    public ApiResponse<Void> removeMember(
            Authentication authentication, @PathVariable Long groupId, @PathVariable Long targetUserId) {
        Long userId = requireUserId(authentication);
        groupService.removeMember(groupId, userId, targetUserId);
        String message = userId.equals(targetUserId) ? "Đã rời nhóm" : "Đã xóa thành viên khỏi nhóm";
        return ApiResponse.success(message, null);
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}