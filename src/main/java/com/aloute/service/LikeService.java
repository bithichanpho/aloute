package com.aloute.service;

import org.springframework.stereotype.Service;

import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
import com.aloute.repository.ILikeRepository;

@Service
public class LikeService {

    private final ILikeRepository likeRepository;
    private final PostService postService;
    private final NotificationService notificationService;

    public LikeService(ILikeRepository likeRepository, PostService postService, NotificationService notificationService) {
        this.likeRepository = likeRepository;
        this.postService = postService;
        this.notificationService = notificationService;
    }

    /** Bật/tắt like — trả về true nếu sau thao tác là ĐÃ like. */
    public boolean toggleLike(String postId, Long currentUserId) {
        String userIdStr = String.valueOf(currentUserId);

        return likeRepository.findByPostIdAndUserId(postId, userIdStr)
                .map(existing -> {
                    likeRepository.delete(existing);
                    postService.adjustLikeCount(postId, -1);
                    return false;
                })
                .orElseGet(() -> {
                    likeRepository.save(new Like(postId, userIdStr));
                    postService.adjustLikeCount(postId, 1);

                    Post post = postService.getPostOrThrow(postId);
                    notificationService.notify(
                            Long.valueOf(post.getUserId()), currentUserId,
                            "LIKE", postId, "đã thích bài viết của bạn"
                    );
                    return true;
                });
    }
}
