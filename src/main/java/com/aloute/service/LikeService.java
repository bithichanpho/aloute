package com.aloute.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.mongodb.client.result.DeleteResult;
import com.aloute.dto.response.LikeResponse;
import com.aloute.model.document.Like;
import com.aloute.repository.ILikeRepository;

@Service
public class LikeService {

    private final ILikeRepository likeRepository;
    private final PostService postService;
    private final NotificationService notificationService;
    private final MongoTemplate mongoTemplate;

    public LikeService(ILikeRepository likeRepository, PostService postService,
            NotificationService notificationService, MongoTemplate mongoTemplate) {
        this.likeRepository = likeRepository;
        this.postService = postService;
        this.notificationService = notificationService;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * API idempotent: client gui TRANG THAI MONG MUON thay vi toggle.
     *
     * Khi like: insert atomically; chi tang likeCount neu insert thanh cong.
     * Neu 2 tab cung like cung luc va bi duplicate-key do unique index, xem nhu
     * da like va KHONG tang count lan nua.
     *
     * Khi unlike: delete atomically; chi giam likeCount neu that su xoa 1 document.
     * Nhờ vậy viec bam like/unlike rat nhanh va khong lam count bi sai do request chong cheo.
     */
    public LikeResponse setLike(String postId, Long currentUserId, boolean desiredLiked) {
        postService.getPostOrThrow(postId);
        String userIdStr = String.valueOf(currentUserId);

        if (desiredLiked) {
            boolean inserted = false;
            try {
                if (!likeRepository.existsByPostIdAndUserId(postId, userIdStr)) {
                    likeRepository.save(new Like(postId, userIdStr));
                    inserted = true;
                }
            } catch (DuplicateKeyException ignored) {
                // Một request song song khác đã tạo like trước -> trạng thái đã là like.
            }

            if (inserted) {
                postService.adjustLikeCount(postId, 1);
                notificationService.notifyPostLiked(postId, currentUserId);
            }
        } else {
            Query query = Query.query(Criteria.where("postId").is(postId).and("userId").is(userIdStr));
            DeleteResult result = mongoTemplate.remove(query, Like.class);
            if (result.getDeletedCount() > 0) {
                postService.adjustLikeCount(postId, -1);
            }
        }

        boolean liked = likeRepository.existsByPostIdAndUserId(postId, userIdStr);
        int likeCount = postService.getPostOrThrow(postId).getLikeCount();
        return new LikeResponse(postId, liked, Math.max(0, likeCount));
    }

    /** Giữ tương thích với code cũ: toggle dựa trên trạng thái hiện tại. */
    public boolean toggleLike(String postId, Long currentUserId) {
        String userIdStr = String.valueOf(currentUserId);
        boolean current = likeRepository.existsByPostIdAndUserId(postId, userIdStr);
        return setLike(postId, currentUserId, !current).isLiked();
    }
}
