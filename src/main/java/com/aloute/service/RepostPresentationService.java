package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Repost;
import com.aloute.model.entity.User;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Maps repost relationships into compact actor previews for post response DTOs. */
@Service
public class RepostPresentationService {
    private final IRepostRepository repostRepository;
    private final IUserRepository userRepository;

    public RepostPresentationService(IRepostRepository repostRepository, IUserRepository userRepository) {
        this.repostRepository = repostRepository;
        this.userRepository = userRepository;
    }

    public void attachRecentReposters(List<PostResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        List<String> postIds =
                responses.stream()
                        .map(PostResponse::getInteractionPostId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        if (postIds.isEmpty()) return;

        List<Repost> reposts = repostRepository.findByPostIdIn(postIds);
        if (reposts.isEmpty()) return;
        Set<Long> userIds =
                reposts.stream().map(r -> Long.valueOf(r.getUserId())).collect(Collectors.toSet());
        Map<Long, User> users =
                userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, List<Repost>> byPost =
                reposts.stream().collect(Collectors.groupingBy(Repost::getPostId));

        for (PostResponse response : responses) {
            List<Repost> rs =
                    new java.util.ArrayList<>(
                            byPost.getOrDefault(
                                    response.getInteractionPostId(), Collections.emptyList()));
            rs.sort(
                    java.util.Comparator.comparing(
                                    Repost::getCreatedAt,
                                    java.util.Comparator.nullsLast(
                                            java.util.Comparator.naturalOrder()))
                            .reversed());
            List<PostResponse.RepostActor> actors =
                    rs.stream()
                            .map(r -> toRepostActor(r, users))
                            .filter(java.util.Objects::nonNull)
                            .toList();
            response.setRecentReposters(actors.stream().limit(3).toList());
            response.setOtherReposters(actors.stream().skip(3).toList());
        }
    }

    private PostResponse.RepostActor toRepostActor(Repost repost, Map<Long, User> users) {
        Long userId = Long.valueOf(repost.getUserId());
        User user = users.get(userId);
        if (user == null) return null;
        PostResponse.RepostActor actor = new PostResponse.RepostActor();
        actor.setUserId(userId);
        actor.setUsername(user.getUsername());
        actor.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        actor.setCreatedAt(repost.getCreatedAt());
        return actor;
    }

    public List<PostResponse.RepostActor> loadRepostActors(String postId) {
        List<Repost> reposts = repostRepository.findByPostIdOrderByCreatedAtDesc(postId);
        if (reposts.isEmpty()) return Collections.emptyList();
        Set<Long> ids =
                reposts.stream().map(r -> Long.valueOf(r.getUserId())).collect(Collectors.toSet());
        Map<Long, User> users =
                userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        return reposts.stream()
                .map(r -> toRepostActor(r, users))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
