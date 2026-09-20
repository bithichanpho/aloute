package com.aloute.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.mongodb.client.result.DeleteResult;
import com.aloute.common.util.ReactionType;
import com.aloute.dto.response.LikeResponse;
import com.aloute.dto.response.ReactionResponse;
import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
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
     * API idempotent CŨ (giữ để tương thích ngược với client cũ chỉ biết like/unlike):
     * liked=true tương đương setReaction(LIKE), liked=false tương đương bỏ reaction.
     */
    public LikeResponse setLike(String postId, Long currentUserId, boolean desiredLiked) {
        ReactionResponse reaction = setReaction(postId, currentUserId, desiredLiked ? ReactionType.LIKE : null);
        return new LikeResponse(postId, reaction.getCurrentUserReaction() != null, reaction.getTotalCount());
    }

    /** Giữ tương thích với code cũ: toggle dựa trên trạng thái hiện tại. */
    public boolean toggleLike(String postId, Long currentUserId) {
        String userIdStr = String.valueOf(currentUserId);
        boolean current = likeRepository.existsByPostIdAndUserId(postId, userIdStr);
        return setLike(postId, currentUserId, !current).isLiked();
    }

    /**
     * Đặt reaction cho bài viết (kiểu Facebook: LIKE/LOVE/HAHA/WOW/SAD/ANGRY).
     *
     * @param requestedType null hoặc rỗng = BỎ reaction hiện tại. Giá trị không hợp lệ tự
     *                      động chuẩn hoá về LIKE (xem {@link ReactionType#normalize}).
     *
     * Quy tắc (giống Facebook):
     * - Chưa có reaction, gửi 1 loại   -> tạo mới loại đó.
     * - Đang có loại A, gửi LẠI đúng A  -> bỏ reaction (coi như bấm lần nữa để tắt).
     * - Đang có loại A, gửi loại B khác -> CHUYỂN sang loại B (không cộng dồn 2 lượt).
     * - Đang có loại A, gửi null        -> bỏ reaction.
     *
     * Toàn bộ thao tác atomic ở tầng DB (insert/delete theo unique index, update $inc riêng
     * cho từng loại) để nhiều tab/nhiều lần bấm liên tiếp không làm sai lệch bộ đếm.
     */
    public ReactionResponse setReaction(String postId, Long currentUserId, String requestedType) {
        Post post = postService.getPostOrThrow(postId);
        String userIdStr = String.valueOf(currentUserId);
        boolean wantsReaction = requestedType != null && !requestedType.isBlank();
        String normalizedType = wantsReaction ? ReactionType.normalize(requestedType) : null;

        Like existing = likeRepository.findByPostIdAndUserId(postId, userIdStr).orElse(null);

        if (!wantsReaction) {
            // Bỏ reaction hiện tại (nếu có).
            if (existing != null) {
                removeReaction(postId, existing);
            }
        } else if (existing == null) {
            // Chưa có reaction nào -> tạo mới.
            try {
                likeRepository.save(new Like(postId, userIdStr, normalizedType));
                postService.adjustReactionCount(postId, normalizedType, 1);
                notifyReacted(post, currentUserId);
            } catch (DuplicateKeyException ignored) {
                // Một request song song khác đã tạo reaction trước -> coi như đã xong, không cộng thêm.
            }
        } else if (normalizedType.equals(existing.getReactionType())) {
            // Bấm lại đúng loại đang có -> tắt reaction (giống bấm nút Like 2 lần).
            removeReaction(postId, existing);
        } else {
            // Chuyển từ loại cũ sang loại mới: giảm loại cũ, tăng loại mới. 2 lệnh dưới đây
            // cũng tự +/-1 vào likeCount tổng nên tổng likeCount không đổi (-1 rồi +1 = 0),
            // không cần điều chỉnh gì thêm.
            String oldType = existing.getReactionType();
            existing.setReactionType(normalizedType);
            likeRepository.save(existing);
            postService.adjustReactionCount(postId, oldType, -1);
            postService.adjustReactionCount(postId, normalizedType, 1);
        }

        Post refreshed = postService.getPostOrThrow(postId);
        Like current = likeRepository.findByPostIdAndUserId(postId, userIdStr).orElse(null);
        Map<String, Integer> counts = refreshed.getReactionCounts() != null
                ? refreshed.getReactionCounts() : new HashMap<>();

        return new ReactionResponse(postId, current != null ? current.getReactionType() : null,
                Math.max(0, refreshed.getLikeCount()), counts);
    }

    private void removeReaction(String postId, Like existing) {
        Query query = Query.query(Criteria.where("postId").is(postId).and("userId").is(existing.getUserId()));
        DeleteResult result = mongoTemplate.remove(query, Like.class);
        if (result.getDeletedCount() > 0) {
            postService.adjustReactionCount(postId, existing.getReactionType(), -1);
        }
    }

    private void notifyReacted(Post post, Long reactorId) {
        try {
            Long ownerId = Long.valueOf(post.getUserId());
            notificationService.notify(ownerId, reactorId, "LIKE", post.getId(), "đã thích bài viết của bạn");
        } catch (org.springframework.core.task.TaskRejectedException ignored) {
            // Hàng đợi thông báo đầy: reaction ĐÃ ghi xong, không để lỗi gửi thông báo làm hỏng thao tác chính.
        }
    }
}