package com.aloute.config;

import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
@Order(3)
@ConditionalOnProperty(name = "app.migrations.legacy-repost.enabled", havingValue = "true")
public class LegacyRepostMigration implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyRepostMigration.class);
    private final PostgresDocumentStore documentStore;
    private final IPostRepository postRepository;
    private final IRepostRepository repostRepository;
    private final ICommentRepository commentRepository;
    private final ILikeRepository likeRepository;

    public LegacyRepostMigration(
            PostgresDocumentStore documentStore,
            IPostRepository postRepository,
            IRepostRepository repostRepository,
            ICommentRepository commentRepository,
            ILikeRepository likeRepository) {
        this.documentStore = documentStore;
        this.postRepository = postRepository;
        this.repostRepository = repostRepository;
        this.commentRepository = commentRepository;
        this.likeRepository = likeRepository;
    }

    @Override
    public void run(String... args) {
        Query q =
                Query.query(
                        new Criteria()
                                .andOperator(
                                        Criteria.where("repostOfId").ne(null),
                                        new Criteria()
                                                .orOperator(
                                                        Criteria.where("content").is(null),
                                                        Criteria.where("content").is(""))));
        List<Post> legacy = documentStore.find(q, Post.class);
        for (Post post : legacy) {
            if (post.getUserId() == null
                    || post.getRepostOfId() == null
                    || !postRepository.existsById(post.getRepostOfId())) continue;
            if (repostRepository
                    .findByUserIdAndPostId(post.getUserId(), post.getRepostOfId())
                    .isEmpty()) {
                Repost r = new Repost();
                r.setUserId(post.getUserId());
                r.setPostId(post.getRepostOfId());
                r.setCreatedAt(post.getCreatedAt());
                repostRepository.save(r);
            }
            commentRepository.deleteByPostId(post.getId());
            likeRepository.deleteByPostId(post.getId());
            postRepository.delete(post);
        }
        if (!legacy.isEmpty()) {
            log.info("Migrated {} legacy plain repost post(s) to repost records.", legacy.size());
        }
    }
}
