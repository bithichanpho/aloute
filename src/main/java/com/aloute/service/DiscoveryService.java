package com.aloute.service;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.aloute.dto.response.TrendingTopicDto;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.Friend;
import com.aloute.model.entity.User;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;

/**
 * Gom logic "khám phá" dùng chung cho trang /feed (rail bên phải) và trang
 * /search (gợi ý + xu hướng).
 *
 * Về hiệu năng: cả hai phần đều được viết theo hướng "số query cố định".
 * Bản đầu tiên nạp toàn bộ bảng users rồi dựng hồ sơ đầy đủ cho từng người
 * (~9 query mỗi người) — đó là lý do trang feed tải chậm dần theo số tài khoản.
 */
@Service
public class DiscoveryService {

    /** Hashtag kiểu #xuhuong — chấp nhận cả chữ có dấu tiếng Việt. */
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,30})");

    /** Số bài viết gần nhất được quét để tính xu hướng. */
    private static final int TRENDING_SCAN_SIZE = 120;

    /** Xu hướng đổi rất chậm nên giữ lại kết quả trong khoảng thời gian này. */
    private static final long TRENDING_TTL_MS = 60_000L;

    private final UserService userService;
    private final GroupService groupService;
    private final IGroupMemberRepository groupMemberRepository;
    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final IFriendRepository friendRepository;

    /** Cache xu hướng dùng chung cho mọi người dùng (dữ liệu không mang tính cá nhân). */
    private final AtomicReference<TrendingCache> trendingCache = new AtomicReference<>();

    public DiscoveryService(
            UserService userService,
            GroupService groupService,
            IGroupMemberRepository groupMemberRepository,
            IPostRepository postRepository,
            IUserRepository userRepository,
            IFriendRepository friendRepository) {
        this.userService = userService;
        this.groupService = groupService;
        this.groupMemberRepository = groupMemberRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
    }

    // ============================================================
    // Gợi ý "Có thể bạn quen"
    // ============================================================

    /**
     * Chi phí: khoảng 8 query cố định, không phụ thuộc số tài khoản trong hệ thống.
     * Trước đây phần này tốn ~9 query × (số user trong DB).
     */
    public List<UserProfileDto> getSuggestedUsers(Long currentUserId, int limit) {
        // Không có sẵn danh sách nhóm (vd: gọi từ SearchController) -> tự query.
        // Nơi nào ĐÃ có sẵn (vd: ViewController.feed() đã load feedGroups) nên gọi
        // overload bên dưới để khỏi tốn thêm 1 query trùng lặp.
        return getSuggestedUsers(currentUserId, limit, null);
    }

    /**
     * @param myGroups Danh sách nhóm hiện tại của user, nếu trang gọi đã tải sẵn (vd trang /feed
     *                 đã cần feedGroups cho rail bên phải). Truyền null nếu chưa có, hàm sẽ tự query.
     *                 Tránh được việc gọi groupService.getMyGroups() 2 lần trong cùng 1 request.
     */
    public List<UserProfileDto> getSuggestedUsers(Long currentUserId, int limit, List<ChatGroup> myGroups) {
        if (currentUserId == null) {
            return Collections.emptyList();
        }
        int size = Math.max(1, Math.min(20, limit));

        // 2 query: quan hệ follow hai chiều của mình
        Set<Long> followingSet = friendRepository.findByFollowerId(currentUserId).stream()
                .map(Friend::getFollowedId).collect(Collectors.toCollection(HashSet::new));
        Set<Long> followerSet = friendRepository.findByFollowedId(currentUserId).stream()
                .map(Friend::getFollowerId).collect(Collectors.toCollection(HashSet::new));

        Set<Long> mutualSet = followingSet.stream()
                .filter(followerSet::contains)
                .collect(Collectors.toCollection(HashSet::new));

        // 1 query: mọi cạnh follow xuất phát từ bạn bè của mình -> vừa là nguồn ứng viên
        // "bạn của bạn", vừa là dữ liệu để đếm bạn chung mà không cần query thêm lần nào.
        Map<Long, Integer> commonFriendCount = new HashMap<>();
        if (!mutualSet.isEmpty()) {
            for (Friend edge : friendRepository.findByFollowerIdIn(mutualSet)) {
                Long candidate = edge.getFollowedId();
                if (candidate.equals(currentUserId) || followingSet.contains(candidate)) continue;
                commonFriendCount.merge(candidate, 1, Integer::sum);
            }
        }

        // Ứng viên: bạn-của-bạn > người đang theo dõi mình > tài khoản mới nhất (1 query)
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

        // Chấm điểm TRƯỚC khi dựng hồ sơ, để chỉ dựng đúng `size` người cần hiển thị
        List<Long> ranked = candidateIds.stream()
                .sorted(Comparator.comparingInt((Long id) -> {
                    int score = commonFriendCount.getOrDefault(id, 0) * 100;
                    if (followerSet.contains(id)) score += 60;
                    return score;
                }).reversed())
                .limit(size)
                .collect(Collectors.toList());

        // ~5 query: dựng hồ sơ cho đúng `size` người
        List<UserProfileDto> suggestions = userService.getProfiles(ranked, currentUserId);

        // Các nhóm mình đang tham gia (để giải thích "Chung nhóm với bạn").
        // Chỉ query nếu chưa được truyền sẵn từ nơi gọi (xem javadoc tham số myGroups).
        List<ChatGroup> groups = myGroups != null ? myGroups : groupService.getMyGroups(currentUserId);
        Set<Long> myGroupIds = groups.stream()
                .map(ChatGroup::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> candidatesInMyGroups = Collections.emptySet();
        if (!myGroupIds.isEmpty() && !suggestions.isEmpty()) {
            candidatesInMyGroups = groupMemberRepository.findByUserIdInAndGroupIdIn(
                    suggestions.stream().map(UserProfileDto::getId).collect(Collectors.toList()), myGroupIds)
                    .stream().map(gm -> gm.getUserId()).collect(Collectors.toSet());
        }

        for (UserProfileDto suggestion : suggestions) {
            suggestion.setSuggestionReason(
                    buildReason(suggestion.getId(), commonFriendCount, followerSet, myGroupIds, candidatesInMyGroups));
        }
        return suggestions;
    }

    private String buildReason(Long userId, Map<Long, Integer> commonFriendCount,
            Set<Long> followerSet, Set<Long> myGroupIds, Set<Long> candidatesInMyGroups) {

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

    // ============================================================
    // Xu hướng
    // ============================================================

    public List<TrendingTopicDto> getTrendingTopics(int limit) {
        int size = Math.max(1, Math.min(20, limit));

        TrendingCache cached = trendingCache.get();
        if (cached != null && !cached.isExpired()) {
            return cached.take(size);
        }

        List<TrendingTopicDto> computed = computeTrendingTopics(20);
        trendingCache.set(new TrendingCache(computed, System.currentTimeMillis()));
        return computed.size() > size ? new ArrayList<>(computed.subList(0, size)) : computed;
    }

    private List<TrendingTopicDto> computeTrendingTopics(int size) {
        List<Post> posts = postRepository
                .findAllOriginalsByOrderByCreatedAtDesc(PageRequest.of(0, TRENDING_SCAN_SIZE))
                .stream()
                .filter(p -> p.getModerationStatus() != ModerationStatus.HIDDEN)
                .filter(p -> p.getContent() != null && !p.getContent().isBlank())
                .collect(Collectors.toList());

        if (posts.isEmpty()) {
            return Collections.emptyList();
        }

        // 1) Ưu tiên hashtag: đây là "chủ đề" rõ ràng nhất mà người dùng tự gắn.
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

        List<TrendingTopicDto> result = byTag.values().stream()
                .sorted(Comparator.comparingLong((Topic t) -> t.score()).reversed())
                .limit(size)
                .map(Topic::toDto)
                .collect(Collectors.toCollection(ArrayList::new));

        // 2) Chưa đủ thì bù bằng các bài viết có tương tác cao nhất gần đây.
        if (result.size() < size) {
            List<Post> mostEngaged = posts.stream()
                    .sorted(Comparator.comparingInt((Post p) -> engagement(p)).reversed())
                    .collect(Collectors.toList());

            for (Post post : mostEngaged) {
                if (result.size() >= size) break;

                String title = shorten(firstLine(post.getContent()), 46);
                if (title.isBlank()) continue;

                String finalTitle = title;
                boolean duplicated = result.stream()
                        .anyMatch(t -> t.getTopic() != null && t.getTopic().equalsIgnoreCase(finalTitle));
                if (duplicated) continue;

                TrendingTopicDto dto = new TrendingTopicDto();
                dto.setTopic(title);
                dto.setDescription(shorten(flatten(post.getContent()), 110));
                dto.setImageUrl(post.getImageUrl());
                dto.setQuery(title);
                dto.setPostCount(1);
                dto.setMeta(engagement(post) > 0
                        ? post.getLikeCount() + " lượt thích · " + post.getCommentCount() + " bình luận"
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

    /** Cắt chuỗi theo ranh giới từ để không cắt ngang giữa một từ. */
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

    /** Bộ đếm cho một hashtag trong lúc quét các bài viết gần đây. */
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

    /** Ảnh chụp danh sách xu hướng kèm thời điểm tính, dùng lại trong TRENDING_TTL_MS. */
    private static final class TrendingCache {

        private final List<TrendingTopicDto> topics;
        private final long computedAt;

        private TrendingCache(List<TrendingTopicDto> topics, long computedAt) {
            this.topics = topics;
            this.computedAt = computedAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - computedAt > TRENDING_TTL_MS;
        }

        private List<TrendingTopicDto> take(int size) {
            return topics.size() > size ? new ArrayList<>(topics.subList(0, size)) : topics;
        }
    }
}