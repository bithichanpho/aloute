package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.CloseFriendService;
import com.aloute.service.UserService;

/**
 * "Bạn thân" (Close friend): gửi lời mời / chấp nhận / từ chối / xóa — mô hình kiểu
 * Facebook "Add friend", nằm TRÊN nền Follow tự do hiện có (xem FriendController).
 * Chỉ gửi được lời mời cho người đã là bạn bè (follow lẫn nhau) — xem CloseFriendService.
 */
@RestController
@RequestMapping("/api/close-friends")
public class CloseFriendController {

    private final CloseFriendService closeFriendService;
    private final UserService userService;

    public CloseFriendController(CloseFriendService closeFriendService, UserService userService) {
        this.closeFriendService = closeFriendService;
        this.userService = userService;
    }

    /** Gửi lời mời kết bạn thân tới userId (hoặc tự động chấp nhận nếu họ đã mời mình trước). */
    @PostMapping("/{userId}/request")
    public ApiResponse<String> sendRequest(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = requireUserId(authentication);
        String status = closeFriendService.sendRequest(currentUserId, userId);
        String message = "ACCEPTED".equals(status) ? "Đã trở thành bạn thân" : "Đã gửi lời mời kết bạn thân";
        return ApiResponse.success(message, status);
    }

    /** Chấp nhận lời mời kết bạn thân đang chờ từ userId. */
    @PostMapping("/{userId}/accept")
    public ApiResponse<String> accept(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = requireUserId(authentication);
        String status = closeFriendService.acceptRequest(currentUserId, userId);
        return ApiResponse.success("Đã chấp nhận kết bạn thân", status);
    }

    /**
     * Hủy quan hệ bạn thân với userId — dùng chung cho hủy lời mời đã gửi, từ chối lời mời
     * đang nhận, hoặc xóa khỏi danh sách bạn thân đã chấp nhận (client tự biết mình đang ở
     * tình huống nào dựa vào trạng thái hiện có trên UI, backend chỉ đơn giản dọn quan hệ).
     */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> remove(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = requireUserId(authentication);
        closeFriendService.removeRelationship(currentUserId, userId);
        return ApiResponse.success("Đã cập nhật", null);
    }

    /** Danh sách bạn thân của 1 user (xem trên trang cá nhân của họ). */
    @GetMapping("/{userId}")
    public ApiResponse<List<UserProfileDto>> list(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        // Dựng theo lô: số query không tăng theo số bạn thân
        List<UserProfileDto> profiles =
                userService.getProfiles(closeFriendService.getCloseFriendIds(userId), currentUserId);
        return ApiResponse.success(profiles);
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}