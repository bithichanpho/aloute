package com.aloute.config;

import com.aloute.model.document.CommentReaction;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.FollowRequest;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.SavedPost;
import com.aloute.model.document.UserBlock;


import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.mongo.run-index-migrations", havingValue = "true")
public class FeatureIndexInitializer implements CommandLineRunner {
    private final MongoTemplate mongo;

    public FeatureIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(String... args) {
        mongo.indexOps(UserBlock.class)
                .createIndex(
                        new Index()
                                .on("ownerId", Direction.ASC)
                                .on("targetId", Direction.ASC)
                                .unique());
        mongo.indexOps(UserBlock.class).createIndex(new Index().on("targetId", Direction.ASC));
        mongo.indexOps(UserBlock.class).createIndex(
                new Index().on("ownerId", Direction.ASC)
                        .on("createdAt", Direction.DESC).on("_id", Direction.DESC));
        mongo.indexOps(FollowRequest.class).createIndex(new Index().on("targetId", Direction.ASC));
        mongo.indexOps(FollowRequest.class).createIndex(
                new Index().on("targetId", Direction.ASC)
                        .on("createdAt", Direction.DESC).on("_id", Direction.DESC));
        mongo.indexOps(SavedPost.class)
                .createIndex(
                        new Index().on("userId", Direction.ASC).on("createdAt", Direction.DESC));
        mongo.indexOps(ContentReport.class)
                .createIndex(
                        new Index().on("status", Direction.ASC).on("createdAt", Direction.ASC));
        mongo.indexOps(PostRevision.class)
                .createIndex(
                        new Index().on("postId", Direction.ASC).on("createdAt", Direction.DESC));
        mongo.indexOps(CommentReaction.class)
                .createIndex(
                        new Index()
                                .on("commentId", Direction.ASC)
                                .on("userId", Direction.ASC)
                                .named("comment_reaction_unique")
                                .unique());
        // Match keyset feed sorts exactly: createdAt DESC, then Mongo's _id DESC tie-breaker.
        mongo.indexOps(Post.class).createIndex(
                new Index().on("createdAt", Direction.DESC).on("_id", Direction.DESC)
                        .named("posts_created_id_desc"));
        mongo.indexOps(Post.class).createIndex(
                new Index().on("userId", Direction.ASC)
                        .on("createdAt", Direction.DESC).on("_id", Direction.DESC)
                        .named("posts_user_created_id_desc"));
        mongo.indexOps(Post.class).createIndex(
                new Index().on("communityId", Direction.ASC)
                        .on("communityPostStatus", Direction.ASC)
                        .on("createdAt", Direction.DESC)
                        .named("posts_community_review_created"));
        mongo.indexOps(Post.class).createIndex(new Index().on("hashtags", Direction.ASC));
        mongo.indexOps(Post.class).createIndex(new Index().on("mentions", Direction.ASC));
    }
}
