package com.aloute.service;

import com.aloute.model.document.Post;
import com.aloute.repository.IPostRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Repairs denormalized post reaction counters from the authoritative likes collection. */
@Service
@ConditionalOnProperty(
        name = "app.maintenance.reaction-reconciliation-enabled", havingValue = "true", matchIfMissing = true)
public class ReactionCountReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReactionCountReconciliationService.class);
    private static final int BATCH_SIZE = 200;

    private final IPostRepository postRepository;
    private final MongoTemplate mongoTemplate;

    public ReactionCountReconciliationService(IPostRepository postRepository, MongoTemplate mongoTemplate) {
        this.postRepository = postRepository;
        this.mongoTemplate = mongoTemplate;
    }

    // lockAtLeastFor outlasts clock skew between replicas that fire the same cron tick;
    // lockAtMostFor is only the crash safety net and must exceed a full scan of the posts.
    @Scheduled(cron = "${app.maintenance.reaction-reconciliation-cron:0 30 3 * * *}")
    @SchedulerLock(name = "reactionCountReconciliation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void runScheduledReconciliation() {
        try {
            int updated = reconcileNow();
            if (updated > 0) log.info("Reconciled reaction counters for {} posts", updated);
        } catch (RuntimeException ex) {
            // Keep the scheduler alive; a later run can repair counts after database recovery.
            log.error("Reaction counter reconciliation failed; it will retry on the next schedule", ex);
        }
    }

    /** Public to permit an operations command or test to trigger reconciliation explicitly. */
    public int reconcileNow() {
        int page = 0;
        int updatedPosts = 0;
        while (true) {
            List<Post> posts = postRepository.findAll(
                    PageRequest.of(page, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "id"))).getContent();
            if (posts.isEmpty()) break;

            List<String> postIds = posts.stream().map(Post::getId).filter(id -> id != null).toList();
            Map<String, Map<String, Integer>> counts = aggregateLikes(postIds);
            for (Post post : posts) {
                if (post.getId() == null) continue;
                Map<String, Integer> byType = counts.getOrDefault(post.getId(), Map.of());
                int total = byType.values().stream().mapToInt(Integer::intValue).sum();
                Query query = Query.query(Criteria.where("_id").is(post.getId()));
                Update update = new Update().set("likeCount", total).set("reactionCounts", byType);
                mongoTemplate.updateFirst(query, update, Post.class);
                updatedPosts++;
            }
            if (posts.size() < BATCH_SIZE) break;
            page++;
        }
        return updatedPosts;
    }

    private Map<String, Map<String, Integer>> aggregateLikes(List<String> postIds) {
        if (postIds.isEmpty()) return Map.of();
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("postId").in(postIds)),
                Aggregation.group("postId", "reactionType").count().as("count"));
        List<Document> rows = mongoTemplate.aggregate(aggregation, "likes", Document.class).getMappedResults();
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (Document row : rows) {
            Object idValue = row.get("_id");
            if (!(idValue instanceof Document groupedKey)) continue;
            Object postIdValue = groupedKey.get("postId");
            if (postIdValue == null) continue;
            String postId = postIdValue.toString();
            Object typeValue = groupedKey.get("reactionType");
            String type = typeValue == null || typeValue.toString().isBlank()
                    ? "LIKE" : typeValue.toString().toUpperCase(java.util.Locale.ROOT);
            Object countValue = row.get("count");
            if (!(countValue instanceof Number count)) continue;
            counts.computeIfAbsent(postId, ignored -> new HashMap<>()).put(type, count.intValue());
        }
        return counts;
    }
}
