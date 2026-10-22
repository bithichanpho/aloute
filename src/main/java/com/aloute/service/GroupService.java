package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.dto.request.CreateGroupRequest;
import com.aloute.dto.response.GroupMemberDto;
import com.aloute.dto.response.GroupResponse;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.GroupMember;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatGroupRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);
    private static final int MAX_GROUP_MEMBERS = 500;

    private final IChatGroupRepository groupRepository;
    private final IGroupMemberRepository memberRepository;
    private final IGroupReadStatusRepository readStatusRepository;
    private final IUserRepository userRepository;
    private final FriendService friendService;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupService(
            IChatGroupRepository groupRepository,
            IGroupMemberRepository memberRepository,
            IGroupReadStatusRepository readStatusRepository,
            IUserRepository userRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.readStatusRepository = readStatusRepository;
        this.userRepository = userRepository;
        this.friendService = friendService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public GroupResponse createGroup(Long creatorId, CreateGroupRequest request) {
        Set<Long> memberIds = new LinkedHashSet<>(request.getMemberIds());
        memberIds.remove(creatorId);
        if (memberIds.isEmpty()) {
            throw new BadRequestException("Nhóm cần ít nhất 1 thành viên khác ngoài bạn");
        }
        for (Long memberId : memberIds) {
            if (!friendService.isFriend(creatorId, memberId)) {
                throw new BadRequestException(
                        "Chỉ có thể thêm bạn bè (đã follow lẫn nhau) vào nhóm");
            }
        }

        ChatGroup group = new ChatGroup();
        group.setName(request.getName());
        group.setCreatedBy(creatorId);
        ChatGroup saved = groupRepository.save(group);

        LocalDateTime joinedAt = LocalDateTime.now();
        List<GroupMember> memberships = new java.util.ArrayList<>(memberIds.size() + 1);
        GroupMember creator = new GroupMember(saved.getId(), creatorId, "ADMIN");
        creator.setJoinedAt(joinedAt);
        memberships.add(creator);
        memberIds.forEach(
                memberId -> {
                    GroupMember member = new GroupMember(saved.getId(), memberId, "MEMBER");
                    member.setJoinedAt(joinedAt);
                    memberships.add(member);
                });
        memberRepository.saveAll(memberships);
        readStatusRepository.saveAll(
                memberships.stream()
                        .map(
                                member -> {
                                    GroupReadStatus status =
                                            new GroupReadStatus(saved.getId(), member.getUserId());
                                    status.setLastReadAt(joinedAt);
                                    return status;
                                })
                        .toList());

        GroupResponse response = getGroupDetail(saved.getId(), creatorId);

        broadcastGroupUpdate(saved.getId(), "created", null, memberIds);
        return response;
    }

    @Transactional
    public void addMember(Long groupId, Long requesterId, Long newUserId) {
        requireGroupExists(groupId);
        requireMember(groupId, requesterId);

        if (!userRepository.existsById(newUserId)) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng cần thêm");
        }
        if (memberRepository.existsByGroupIdAndUserId(groupId, newUserId)) {
            throw new BadRequestException("Người này đã ở trong nhóm");
        }
        if (memberRepository.countByGroupId(groupId) >= MAX_GROUP_MEMBERS) {
            throw new BadRequestException("Nhóm đã đạt giới hạn 500 thành viên");
        }
        if (!friendService.isFriend(requesterId, newUserId)) {
            throw new BadRequestException("Chỉ có thể thêm bạn bè (đã follow lẫn nhau) vào nhóm");
        }
        LocalDateTime joinedAt = LocalDateTime.now();
        GroupMember member = new GroupMember(groupId, newUserId, "MEMBER");
        member.setJoinedAt(joinedAt);
        memberRepository.save(member);
        GroupReadStatus status = new GroupReadStatus(groupId, newUserId);
        status.setLastReadAt(joinedAt);
        readStatusRepository.save(status);

        broadcastGroupUpdate(groupId, "member_added", newUserId, getMemberIds(groupId));
    }

    @Transactional
    public void removeMember(Long groupId, Long requesterId, Long targetUserId) {
        requireGroupExists(groupId);
        GroupMember requesterMembership = requireMember(groupId, requesterId);

        boolean isSelfLeave = requesterId.equals(targetUserId);
        boolean requesterIsAdmin = "ADMIN".equals(requesterMembership.getRole());

        if (!isSelfLeave && !requesterIsAdmin) {
            throw new ForbiddenException("Chỉ quản trị viên nhóm mới được xóa thành viên khác");
        }

        List<Long> memberIdsBeforeRemoval = getMemberIds(groupId);

        List<GroupMember> remaining =
                memberRepository.findByGroupId(groupId).stream()
                        .filter(gm -> !gm.getUserId().equals(targetUserId))
                        .collect(Collectors.toList());

        if (isSelfLeave && requesterIsAdmin && !remaining.isEmpty()) {
            boolean anotherAdminExists =
                    remaining.stream().anyMatch(gm -> "ADMIN".equals(gm.getRole()));
            if (!anotherAdminExists) {
                GroupMember promoted = remaining.get(0);
                promoted.setRole("ADMIN");
                memberRepository.save(promoted);
            }
        }

        memberRepository.deleteByGroupIdAndUserId(groupId, targetUserId);
        readStatusRepository.deleteByGroupIdAndUserId(groupId, targetUserId);

        if (remaining.isEmpty()) {
            groupRepository.deleteById(groupId);
        }

        broadcastGroupUpdate(
                groupId,
                isSelfLeave ? "member_left" : "member_removed",
                targetUserId,
                memberIdsBeforeRemoval);
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
        dto.setMembers(getMemberDtos(groupId, 0, 100));
        dto.setMemberCount(getMemberCount(groupId));
        return dto;
    }

    @Transactional
    public GroupResponse updateGroup(
            Long groupId, Long requesterId, String name, String avatarUrl) {
        ChatGroup group = requireGroupExists(groupId);
        GroupMember membership = requireMember(groupId, requesterId);
        if (!"ADMIN".equals(membership.getRole())) {
            throw new ForbiddenException("Chỉ quản trị viên nhóm được chỉnh sửa nhóm");
        }
        group.setName(name.trim());
        group.setAvatarUrl(avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl.trim());
        groupRepository.save(group);
        broadcastGroupUpdate(groupId, "updated", null, getMemberIds(groupId));
        return getGroupDetail(groupId, requesterId);
    }

    public List<ChatGroup> getMyGroups(Long userId) {
        return getMyGroups(userId, 0, 100);
    }

    public List<ChatGroup> getMyGroups(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<Long> groupIds = memberRepository
                .findByUserId(userId, org.springframework.data.domain.PageRequest.of(safePage, safeSize))
                .stream()
                .map(GroupMember::getGroupId)
                .collect(Collectors.toList());
        if (groupIds.isEmpty()) return List.of();
        return groupRepository.findByIdIn(groupIds);
    }

    /** Lightweight membership projection used by PostgreSQL-backed inbox queries. */
    @Transactional(readOnly = true)
    public List<Long> getAllMyGroupIds(Long userId) {
        return memberRepository.findGroupIdsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<ChatGroup> getGroupsByIds(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return List.of();
        return groupRepository.findByIdIn(new java.util.ArrayList<>(groupIds));
    }

    public List<GroupMemberDto> getMembers(Long groupId, Long requesterId, int page, int size) {
        requireGroupExists(groupId);
        requireMember(groupId, requesterId);
        return getMemberDtos(groupId, page, size);
    }

    public boolean isMember(Long groupId, Long userId) {
        return memberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    public List<Long> getMemberIds(Long groupId) {
        return memberRepository.findByGroupId(groupId).stream()
                .map(GroupMember::getUserId)
                .collect(Collectors.toList());
    }

    public Map<Long, Integer> getMemberCounts(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return memberRepository.countMembers(ids).stream()
                .collect(
                        Collectors.toMap(
                                IGroupMemberRepository.MemberCount::getGroupId,
                                c -> c.getTotal().intValue()));
    }

    public int getMemberCount(Long groupId) {
        return (int) memberRepository.countByGroupId(groupId);
    }

    private List<GroupMemberDto> getMemberDtos(Long groupId, int page, int size) {
        List<GroupMember> members = memberRepository.findByGroupId(
                groupId, org.springframework.data.domain.PageRequest.of(page, size));
        Map<Long, User> users =
                userRepository
                        .findAllById(members.stream().map(GroupMember::getUserId).toList())
                        .stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        return members.stream()
                .map(
                        gm -> {
                            GroupMemberDto dto = new GroupMemberDto();
                            dto.setUserId(gm.getUserId());
                            dto.setRole(gm.getRole());
                            User u = users.get(gm.getUserId());
                            if (u != null) {
                                dto.setUsername(u.getUsername());
                                dto.setFullName(u.getFullName());
                                dto.setAvatarUrl(AvatarUtil.resolve(u.getAvatarUrl()));
                            }
                            return dto;
                        })
                .collect(Collectors.toList());
    }

    private ChatGroup requireGroupExists(Long groupId) {
        return groupRepository
                .findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm"));
    }

    private GroupMember requireMember(Long groupId, Long userId) {
        return memberRepository
                .findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(
                        () -> new ForbiddenException("Bạn không phải thành viên của nhóm này"));
    }

    private void broadcastGroupUpdate(
            Long groupId, String action, Long affectedUserId, Collection<Long> recipientUserIds) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("groupId", groupId);
        payload.put("action", action);
        payload.put("affectedUserId", affectedUserId);
        Runnable broadcast = () -> {
            try {
                for (User user : userRepository.findAllById(recipientUserIds)) {
                    if (user.isActive()) {
                        messagingTemplate.convertAndSendToUser(
                                user.getUsername(), "/queue/group-updates", payload);
                    }
                }
            } catch (RuntimeException ex) {
                // SQL has already committed. A transient WebSocket failure must not make the
                // client believe group creation/update rolled back; group state can be reloaded.
                log.warn("Group update broadcast failed after commit (groupId={}, action={}, cause={})",
                        groupId, action, ex.getClass().getSimpleName());
            }
        };
        // Don't publish group state before PostgreSQL commits; clients could otherwise observe
        // a group/member update that was later rolled back.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { broadcast.run(); }
            });
        } else {
            broadcast.run();
        }
    }
}
