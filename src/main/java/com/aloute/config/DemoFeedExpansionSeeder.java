package com.aloute.config;

import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.model.entity.User;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
@Order(5)
public class DemoFeedExpansionSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoFeedExpansionSeeder.class);
    private static final String MARKER = "[DEMO-EXPANSION]";

    private final boolean enabled;
    private final IUserRepository userRepository;
    private final IPostRepository postRepository;
    private final IRepostRepository repostRepository;
    private final PostgresDocumentStore documentStore;

    public DemoFeedExpansionSeeder(
            SeedProperties seedProperties,
            IUserRepository userRepository,
            IPostRepository postRepository,
            IRepostRepository repostRepository,
            PostgresDocumentStore documentStore) {
        this.enabled = seedProperties.isDemoData();
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.repostRepository = repostRepository;
        this.documentStore = documentStore;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;

        List<User> users = new ArrayList<>();
        for (String username :
                List.of(
                        "demo.minh",
                        "demo.thao",
                        "demo.quang",
                        "demo.linh",
                        "demo.nam",
                        "demo.vy",
                        "demo.khoa",
                        "demo.anh",
                        "demo.my",
                        "demo.phuc",
                        "demo.han",
                        "demo.tuan")) {
            userRepository.findByUsername(username).ifPresent(users::add);
        }
        if (users.size() < 4) return;

        List<Post> demoPosts = findExpandedPosts();
        if (demoPosts.size() < 60) {
            demoPosts = new ArrayList<>(demoPosts);
            demoPosts.addAll(createMorePosts(users, 60 - demoPosts.size()));
            log.info("Demo feed expansion now has {} marked posts.", demoPosts.size());
        }

        seedReposts(users, demoPosts);
    }

    private List<Post> findExpandedPosts() {
        Query q = Query.query(Criteria.where("content").regex("^\\[DEMO-EXPANSION\\]"));
        q.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return documentStore.find(q, Post.class);
    }

    private List<Post> createMorePosts(List<User> users, int amount) {
        if (amount <= 0) return List.of();

        String[] texts = {
            "Hôm nay mình test lại feed và khả năng sắp xếp bài viết theo thời gian.",
            "Spring Boot + PostgreSQL + PostgreSQL chạy cùng nhau khá thú vị.",
            "Đang thử luồng reaction nhiều emoji trên ALOUTE.",
            "Một buổi tối debug WebSocket và finally mọi thứ đã chạy ổn.",
            "Chia sẻ chút kinh nghiệm làm đồ án web cho mọi người.",
            "UI đẹp không chỉ là màu sắc mà còn là khoảng trắng và nhịp điệu.",
            "Mọi người thích feed kiểu chronological hay có gợi ý ưu tiên?",
            "Đang test community, group chat và notification cùng lúc.",
            "Một query tốt có thể giúp trang nhanh hơn rất nhiều.",
            "Hôm nay học thêm được một mẹo nhỏ về PostgreSQL index."
        };

        LocalDateTime now = LocalDateTime.now();
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            User author = users.get(i % users.size());
            Post p = new Post();
            p.setUserId(String.valueOf(author.getId()));
            p.setContent(
                    MARKER
                            + " "
                            + texts[i % texts.length]
                            + "\n#ALOUTE #demo #feed #realtime\nBài mở rộng số "
                            + (i + 1)
                            + ".");
            p.setHashtags(com.aloute.service.ContentTagService.extract(p.getContent(), '#'));
            p.setImageUrl(i % 3 == 0 ? "/images/demo/demo-post-" + ((i % 6) + 1) + ".svg" : null);
            p.setLikeCount(0);
            p.setCommentCount(0);
            p.setRepostCount(0);
            p.setReactionCounts(new HashMap<>());
            p.setCreatedAt(now.minusMinutes(2L + i * 9L));
            posts.add(p);
        }
        return postRepository.saveAll(posts);
    }

    private void seedReposts(List<User> users, List<Post> posts) {
        if (posts.isEmpty()) return;

        int created = 0;
        int[][] plan = {
            {1, 2, 3, 4, 5},
            {2, 3, 4, 5},
            {1, 4, 5, 6},
            {3, 4, 5, 6, 7},
            {1, 2, 5, 7},
            {2, 4, 6, 8},
            {1, 3, 7, 9},
            {3, 5, 6, 8, 10},
            {1, 4, 8},
            {2, 5, 9, 10}
        };

        for (int i = 0; i < Math.min(16, posts.size()); i++) {
            Post post = posts.get(i);
            int[] reposters = plan[i % plan.length];
            for (int idx : reposters) {
                if (idx >= users.size()) continue;
                User u = users.get(idx);
                if (u.getId() == null) continue;
                if (repostRepository
                        .findByUserIdAndPostId(String.valueOf(u.getId()), post.getId())
                        .isPresent()) {
                    continue;
                }
                Repost r = new Repost();
                r.setUserId(String.valueOf(u.getId()));
                r.setPostId(post.getId());
                r.setCreatedAt(LocalDateTime.now().minusMinutes((i % 6) + idx));
                repostRepository.save(r);
                created++;
            }
            post.setRepostCount(
                    (int)
                            Math.min(
                                    Integer.MAX_VALUE,
                                    repostRepository.countByPostId(post.getId())));
            postRepository.save(post);
        }

        log.info("Demo feed repost relations ensured; new relations={}", created);
        log.debug("Demo repost distribution initialized for feed UI testing.");
    }
}
