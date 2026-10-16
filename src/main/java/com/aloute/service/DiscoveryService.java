package com.aloute.service;

import com.aloute.dto.response.TrendingTopicDto;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.model.document.Post;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.Friend;
import com.aloute.model.entity.User;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DiscoveryService {
    private final ContentAccessService access;

    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,30})");

    private static final int TRENDING_SCAN_SIZE = 120;

    private final UserService userService;
    private final GroupService groupService;
    private final IGroupMemberRepository groupMemberRepository;
    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final IFriendRepository friendRepository;

    public DiscoveryService(
            UserService userService,
            GroupService groupService,
            IGroupMemberRepository groupMemberRepository,
            IPostRepository postRepository,
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            ContentAccessService access) {
        this.access = access;

        this.userService = userService;
        this.groupService = groupService;
        this.groupMemberRepository = groupMemberRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
    }

    public List<UserProfileDto> getSuggestedUsers(Long currentUserId, int limit) {

        return getSuggestedUsers(currentUserId, limit, null);
    }

    public List<UserProfileDto> getSuggestedUsers(
            Long currentUserId, int limit, List<ChatGroup> myGroups) {
        if (currentUserId == null) {
            return Collections.emptyList();
        }
        int size = Math.max(1, Math.min(20, limit));

        Set<Long> followingSet =
                friendRepository.findByFollowerId(currentUserId).stream()
                        .map(Friend::getFollowedId)
                        .collect(Collectors.toCollection(HashSet::new));
        Set<Long> followerSet =
                friendRepository.findByFollowedId(currentUserId).stream()
                        .map(Friend::getFollowerId)
                        .collect(Collectors.toCollection(HashSet::new));

        Set<Long> mutualSet =
                followingSet.stream()
                        .filter(followerSet::contains)
                        .collect(Collectors.toCollection(HashSet::new));

        Map<Long, Integer> commonFriendCount = new HashMap<>();
        if (!mutualSet.isEmpty()) {
            for (Friend edge : friendRepository.findByFollowerIdIn(mutualSet)) {
                Long candidate = edge.getFollowedId();
                if (candidate.equals(currentUserId) || followingSet.contains(candidate)) continue;
                commonFriendCount.merge(candidate, 1, Integer::sum);
            }
        }

        LinkedHashSet<Long> candidateIds = new LinkedHashSet<>(commonFriendCount.keySet());
        followerSet.stream().filter(id -> !followingSet.contains(id)).forEach(candidateIds::add);

        if (candidateIds.size() < size * 3) {
            userRepository.findTop60ByOrderByIdDesc().stream()
                    .map(User::getId)
                    .filter(id -> !followingSet.contains(id) && !id.equals(currentUserId))
                    .forEach(candidateIds::add);
        }
        candidateIds.remove(currentUserId);

        if (candidateIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ranked =
                candidateIds.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (Long id) -> {
                                                    int score =
                                                            commonFriendCount.getOrDefault(id, 0)
                                                                    * 100;
                                                    if (followerSet.contains(id)) score += 60;
                                                    return score;
                                                })
                                        .reversed())
                        .limit(size)
                        .collect(Collectors.toList());

        List<UserProfileDto> suggestions = userService.getProfiles(ranked, currentUserId);

        List<ChatGroup> groups =
                myGroups != null ? myGroups : groupService.getMyGroups(currentUserId);
        Set<Long> myGroupIds =
                groups.stream()
                        .map(ChatGroup::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        Set<Long> candidatesInMyGroups = Collections.emptySet();
        if (!myGroupIds.isEmpty() && !suggestions.isEmpty()) {
            candidatesInMyGroups =
                    groupMemberRepository
                            .findByUserIdInAndGroupIdIn(
                                    suggestions.stream()
                                            .map(UserProfileDto::getId)
                                            .collect(Collectors.toList()),
                                    myGroupIds)
                            .stream()
                            .map(gm -> gm.getUserId())
                            .collect(Collectors.toSet());
        }

        for (UserProfileDto suggestion : suggestions) {
            suggestion.setSuggestionReason(
                    buildReason(
                            suggestion.getId(),
                            commonFriendCount,
                            followerSet,
                            candidatesInMyGroups));
        }
        return suggestions;
    }

    private String buildReason(
            Long userId,
            Map<Long, Integer> commonFriendCount,
            Set<Long> followerSet,
            Set<Long> candidatesInMyGroups) {

        int common = commonFriendCount.getOrDefault(userId, 0);
        if (common > 0) {
            return common == 1 ? "Có 1 bạn chung" : "Có " + common + " bạn chung";
        }
        if (followerSet.contains(userId)) {
            return "Đang theo dõi bạn";
        }
        boolean sameGroup = candidatesInMyGroups.contains(userId);
        if (sameGroup) {
            return "Chung nhóm với bạn";
        }
        return "Có thể bạn quen";
    }

    @Cacheable(cacheNames = "trendingTopics", key = "#limit")
    public List<TrendingTopicDto> getTrendingTopics(int limit) {
        int size = Math.max(1, Math.min(20, limit));

        return computeTrendingTopics(size);
    }

    private List<TrendingTopicDto> computeTrendingTopics(int size) {
        List<Post> posts =
                postRepository
                        .findAllOriginalsByOrderByCreatedAtDesc(
                                PageRequest.of(0, TRENDING_SCAN_SIZE))
                        .stream()
                        .filter(p -> p.getContent() != null && !p.getContent().isBlank())
                        .collect(Collectors.toList());

        posts = access.forPosts(posts, null).readable(posts);

        if (posts.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Topic> byTag = new LinkedHashMap<>();
        for (Post post : posts) {
            Matcher matcher = HASHTAG.matcher(post.getContent());
            Set<String> seenInThisPost = new HashSet<>();
            while (matcher.find()) {
                String raw = matcher.group(1);
                String key = raw.toLowerCase();
                if (!seenInThisPost.add(key)) continue;
                byTag.computeIfAbsent(key, k -> new Topic("#" + raw)).add(post);
            }
        }

        List<TrendingTopicDto> result =
                byTag.values().stream()
                        .sorted(Comparator.comparingLong((Topic t) -> t.score()).reversed())
                        .limit(size)
                        .map(Topic::toDto)
                        .collect(Collectors.toCollection(ArrayList::new));

        if (result.size() < size) {
            List<Post> mostEngaged =
                    posts.stream()
                            .sorted(Comparator.comparingInt((Post p) -> engagement(p)).reversed())
                            .collect(Collectors.toList());

            for (Post post : mostEngaged) {
                if (result.size() >= size) break;

                String title = shorten(firstLine(post.getContent()), 46);
                if (title.isBlank()) continue;

                String finalTitle = title;
                boolean duplicated =
                        result.stream()
                                .anyMatch(
                                        t ->
                                                t.getTopic() != null
                                                        && t.getTopic()
                                                                .equalsIgnoreCase(finalTitle));
                if (duplicated) continue;

                TrendingTopicDto dto = new TrendingTopicDto();
                dto.setTopic(title);
                dto.setDescription(shorten(flatten(post.getContent()), 110));
                dto.setImageUrl(post.getImageUrl());
                dto.setQuery(title);
                dto.setPostCount(1);
                dto.setMeta(
                        engagement(post) > 0
                                ? post.getLikeCount()
                                        + " lượt thích · "
                                        + post.getCommentCount()
                                        + " bình luận"
                                : "Bài viết mới");
                result.add(dto);
            }
        }
        return result;
    }

    private static int engagement(Post post) {
        return post.getLikeCount() * 2 + post.getCommentCount();
    }

    private static String firstLine(String content) {
        if (content == null) return "";
        for (String line : content.split("\\r?\\n")) {
            if (!line.isBlank()) return line.trim();
        }
        return "";
    }

    private static String flatten(String content) {
        if (content == null) return "";
        return content.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String text, int maxLength) {
        if (text == null) return "";
        String cleaned = flatten(text);
        if (cleaned.length() <= maxLength) return cleaned;

        String cut = cleaned.substring(0, maxLength);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > maxLength / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.trim() + "…";
    }

    private static final class Topic {

        private final String label;
        private long postCount;
        private long engagementScore;
        private Post highlight;

        private Topic(String label) {
            this.label = label;
        }

        private void add(Post post) {
            postCount++;
            engagementScore += engagement(post);
            if (highlight == null || engagement(post) > engagement(highlight)) {
                highlight = post;
            }
        }

        private long score() {
            return postCount * 10 + engagementScore;
        }

        private TrendingTopicDto toDto() {
            TrendingTopicDto dto = new TrendingTopicDto();
            dto.setTopic(label);
            dto.setQuery(label);
            dto.setPostCount(postCount);
            dto.setMeta(postCount + " bài viết");
            if (highlight != null) {
                dto.setDescription(shorten(flatten(highlight.getContent()), 110));
                dto.setImageUrl(highlight.getImageUrl());
            }
            return dto;
        }
    }
}
