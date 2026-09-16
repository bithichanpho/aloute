package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.request.CreateGroupRequest;
import com.aloute.dto.response.GroupMemberDto;
import com.aloute.dto.response.GroupResponse;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.GroupMember;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatGroupRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final IChatGroupRepository groupRepository;
    private final IGroupMemberRepository memberRepository;
    private final IUserRepository userRepository;
    private final FriendService friendService;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupService(
            IChatGroupRepository groupRepository,
            IGroupMemberRepository memberRepository,
            IUserRepository userRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.friendService = friendService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Tạo nhóm mới. Mỗi thành viên được thêm (ngoài người tạo) BẮT BUỘC phải là
     * bạn bè (follow lẫn nhau) với người tạo — cùng nguyên tắc với chat 1-1, để
     * không ai bị kéo vào nhóm bởi người mình không hề follow lại.
     */
    public GroupResponse createGroup(Long creatorId, CreateGroupRequest request) {
        Set<Long> memberIds = new LinkedHashSet<>(request.getMemberIds());
        memberIds.remove(creatorId);
        if (memberIds.isEmpty()) {
            throw new BadRequestException("Nhóm cần ít nhất 1 thành viên khác ngoài bạn");
        }
        for (Long memberId : memberIds) {
            if (!friendService.isFriend(creatorId, memberId)) {
                throw new BadRequestException("Chỉ có thể thêm bạn bè (đã follow lẫn nhau) vào nhóm");
            }
        }

        ChatGroup group = new ChatGroup();
        group.setName(request.getName());
        group.setCreatedBy(creatorId);
        ChatGroup saved = groupRepository.save(group);

        memberRepository.save(new GroupMember(saved.getId(), creatorId, "ADMIN"));
        for (Long memberId : memberIds) {
            memberRepository.save(new GroupMember(saved.getId(), memberId, "MEMBER"));
        }

        GroupResponse response = getGroupDetail(saved.getId(), creatorId);
        // Báo cho các thành viên KHÁC (không phải người tạo) biết họ vừa được thêm vào 1 nhóm mới
        broadcastGroupUpdate(saved.getId(), "created", null, memberIds);
        return response;
    }

    /**
     * Thêm 1 thành viên vào nhóm đã có. Người thêm phải đang ở trong nhóm, và
     * phải là bạn bè với người được thêm (không thêm hộ người mình không quen).
     */
    public void addMember(Long groupId, Long requesterId, Long newUserId) {
        requireGroupExists(groupId);
        requireMember(groupId, requesterId);

        if (!userRepository.existsById(newUserId)) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng cần thêm");
        }
        if (memberRepository.existsByGroupIdAndUserId(groupId, newUserId)) {
            throw new BadRequestException("Người này đã ở trong nhóm");
        }
        if (!friendService.isFriend(requesterId, newUserId)) {
            throw new BadRequestException("Chỉ có thể thêm bạn bè (đã follow lẫn nhau) vào nhóm");
        }
        memberRepository.save(new GroupMember(groupId, newUserId, "MEMBER"));

        broadcastGroupUpdate(groupId, "member_added", newUserId, getMemberIds(groupId));
    }

    /**
     * requesterId tự rời nhóm khi targetUserId == requesterId, hoặc ADMIN xóa người khác.
     * Nếu ADMIN cuối cùng tự rời mà nhóm còn người khác, tự động đẩy 1 thành viên
     * còn lại lên ADMIN để nhóm không bao giờ "mồ côi" quản trị viên. Nhóm rỗng
     * hoàn toàn (người cuối cùng rời đi) sẽ bị xóa luôn, tránh rác dữ liệu.
     */
    public void removeMember(Long groupId, Long requesterId, Long targetUserId) {
        requireGroupExists(groupId);
        GroupMember requesterMembership = requireMember(groupId, requesterId);

        boolean isSelfLeave = requesterId.equals(targetUserId);
        boolean requesterIsAdmin = "ADMIN".equals(requesterMembership.getRole());

        if (!isSelfLeave && !requesterIsAdmin) {
            throw new UnauthorizedException("Chỉ quản trị viên nhóm mới được xóa thành viên khác");
        }

        List<Long> memberIdsBeforeRemoval = getMemberIds(groupId);

        List<GroupMember> remaining = memberRepository.findByGroupId(groupId).stream()
                .filter(gm -> !gm.getUserId().equals(targetUserId))
                .collect(Collectors.toList());

        if (isSelfLeave && requesterIsAdmin && !remaining.isEmpty()) {
            boolean anotherAdminExists = remaining.stream().anyMatch(gm -> "ADMIN".equals(gm.getRole()));
            if (!anotherAdminExists) {
                GroupMember promoted = remaining.get(0);
                promoted.setRole("ADMIN");
                memberRepository.save(promoted);
            }
        }

        memberRepository.deleteByGroupIdAndUserId(groupId, targetUserId);

        if (remaining.isEmpty()) {
            groupRepository.deleteById(groupId);
        }

        broadcastGroupUpdate(groupId, isSelfLeave ? "member_left" : "member_removed", targetUserId, memberIdsBeforeRemoval);
    }

    public GroupResponse getGroupDetail(Long groupId, Long currentUserId) {
        ChatGroup group = requireGroupExists(groupId);
        requireMember(groupId, currentUserId);

        GroupResponse dto = new GroupResponse();
        dto.setId(group.getId());
        dto.setName(group.getName());
        dto.setAvatarUrl(group.getAvatarUrl());
        dto.setCreatedBy(group.getCreatedBy());
        dto.setCreatedAt(group.getCreatedAt());
        dto.setMembers(getMemberDtos(groupId));
        return dto;
    }

    public List<ChatGroup> getMyGroups(Long userId) {
        List<Long> groupIds = memberRepository.findByUserId(userId).stream()
                .map(GroupMember::getGroupId)
                .collect(Collectors.toList());
        return groupRepository.findByIdIn(groupIds);
    }

    public boolean isMember(Long groupId, Long userId) {
        return memberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    public List<Long> getMemberIds(Long groupId) {
        return memberRepository.findByGroupId(groupId).stream()
                .map(GroupMember::getUserId)
                .collect(Collectors.toList());
    }

    public int getMemberCount(Long groupId) {
        return (int) memberRepository.countByGroupId(groupId);
    }

    private List<GroupMemberDto> getMemberDtos(Long groupId) {
        return memberRepository.findByGroupId(groupId).stream().map(gm -> {
            GroupMemberDto dto = new GroupMemberDto();
            dto.setUserId(gm.getUserId());
            dto.setRole(gm.getRole());
            userRepository.findById(gm.getUserId()).ifPresent((User u) -> {
                dto.setUsername(u.getUsername());
                dto.setFullName(u.getFullName());
                dto.setAvatarUrl(AvatarUtil.resolve(u.getAvatarUrl()));
            });
            return dto;
        }).collect(Collectors.toList());
    }

    private ChatGroup requireGroupExists(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm"));
    }

    private GroupMember requireMember(Long groupId, Long userId) {
        return memberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new UnauthorizedException("Bạn không phải thành viên của nhóm này"));
    }

    /**
     * Báo real-time cho danh sách userId liên quan biết nhóm vừa thay đổi (vừa
     * tạo / thêm người / có người rời / bị xóa) để client tự cập nhật lại danh
     * sách hội thoại hoặc khung thành viên đang mở, không cần F5 trang.
     * affectedUserId (có thể null) là người vừa được thêm/bị xóa/rời đi — giúp
     * client phân biệt "chính mình" bị ảnh hưởng hay chỉ là người khác trong nhóm.
     */
    private void broadcastGroupUpdate(Long groupId, String action, Long affectedUserId, Collection<Long> recipientUserIds) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("groupId", groupId);
        payload.put("action", action);
        payload.put("affectedUserId", affectedUserId);
        for (Long uid : recipientUserIds) {
            userRepository.findById(uid).ifPresent(u ->
                    messagingTemplate.convertAndSendToUser(u.getUsername(), "/queue/group-updates", payload));
        }
    }
}