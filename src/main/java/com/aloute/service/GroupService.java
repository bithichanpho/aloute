package com.aloute.service;

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
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final IChatGroupRepository groupRepository;
    private final IGroupMemberRepository memberRepository;
    private final IUserRepository userRepository;

    public GroupService(
            IChatGroupRepository groupRepository,
            IGroupMemberRepository memberRepository,
            IUserRepository userRepository) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    public GroupResponse createGroup(Long creatorId, CreateGroupRequest request) {
        // Gộp danh sách thành viên được chọn + người tạo, loại trùng, phải có ít nhất 2 người
        Set<Long> memberIds = new LinkedHashSet<>(request.getMemberIds());
        memberIds.remove(creatorId);
        if (memberIds.isEmpty()) {
            throw new BadRequestException("Nhóm cần ít nhất 1 thành viên khác ngoài bạn");
        }

        ChatGroup group = new ChatGroup();
        group.setName(request.getName());
        group.setCreatedBy(creatorId);
        ChatGroup saved = groupRepository.save(group);

        memberRepository.save(new GroupMember(saved.getId(), creatorId, "ADMIN"));
        for (Long memberId : memberIds) {
            if (!userRepository.existsById(memberId)) continue; // bỏ qua id không tồn tại
            memberRepository.save(new GroupMember(saved.getId(), memberId, "MEMBER"));
        }

        return getGroupDetail(saved.getId(), creatorId);
    }

    public void addMember(Long groupId, Long requesterId, Long newUserId) {
        requireGroupExists(groupId);
        requireMember(groupId, requesterId);

        if (!userRepository.existsById(newUserId)) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng cần thêm");
        }
        if (memberRepository.existsByGroupIdAndUserId(groupId, newUserId)) {
            throw new BadRequestException("Người này đã ở trong nhóm");
        }
        memberRepository.save(new GroupMember(groupId, newUserId, "MEMBER"));
    }

    /** requesterId tự rời nhóm khi targetUserId == requesterId, hoặc ADMIN xóa người khác. */
    public void removeMember(Long groupId, Long requesterId, Long targetUserId) {
        requireGroupExists(groupId);
        GroupMember requesterMembership = requireMember(groupId, requesterId);

        boolean isSelfLeave = requesterId.equals(targetUserId);
        boolean requesterIsAdmin = "ADMIN".equals(requesterMembership.getRole());

        if (!isSelfLeave && !requesterIsAdmin) {
            throw new UnauthorizedException("Chỉ quản trị viên nhóm mới được xóa thành viên khác");
        }
        memberRepository.deleteByGroupIdAndUserId(groupId, targetUserId);
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
                dto.setAvatarUrl(u.getAvatarUrl());
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
}