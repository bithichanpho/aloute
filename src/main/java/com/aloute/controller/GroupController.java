package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.validation.HttpUrl;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.AddGroupMemberRequest;
import com.aloute.dto.request.CreateGroupRequest;
import com.aloute.dto.response.GroupResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.GroupService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/chat/groups")
@Validated
public class GroupController {
    public record UpdateGroupRequest(
            @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Size(max = 100) String name,
            @jakarta.validation.constraints.Size(max = 2048)
                    @HttpUrl String avatarUrl) {}

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
    public ApiResponse<GroupResponse> getGroup(
            Authentication authentication, @PathVariable @Positive(message = "Mã nhóm không hợp lệ") Long groupId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(groupService.getGroupDetail(groupId, userId));
    }

    @PostMapping("/{groupId}/members")
    public ApiResponse<GroupResponse> addMember(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã nhóm không hợp lệ") Long groupId,
            @Valid @RequestBody AddGroupMemberRequest request) {
        Long userId = requireUserId(authentication);
        groupService.addMember(groupId, userId, request.getUserId());
        return ApiResponse.success(
                "Đã thêm thành viên", groupService.getGroupDetail(groupId, userId));
    }

    @DeleteMapping("/{groupId}/members/{targetUserId}")
    public ApiResponse<Void> removeMember(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã nhóm không hợp lệ") Long groupId,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long targetUserId) {
        Long userId = requireUserId(authentication);
        groupService.removeMember(groupId, userId, targetUserId);
        String message =
                userId.equals(targetUserId) ? "Đã rời nhóm" : "Đã xóa thành viên khỏi nhóm";
        return ApiResponse.success(message, null);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{groupId}")
    public ApiResponse<GroupResponse> updateGroup(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã nhóm không hợp lệ") Long groupId,
            @Valid @RequestBody UpdateGroupRequest request) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(
                groupService.updateGroup(groupId, userId, request.name(), request.avatarUrl()));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
