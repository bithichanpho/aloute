package com.aloute.config;

import com.aloute.model.document.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeatureIndexInitializer implements CommandLineRunner {
    private final MongoTemplate mongo;

    @Override
    public void run(String... args) {
        mongo.indexOps(UserBlock.class)
                .createIndex(
                        new Index()
                                .on("ownerId", Direction.ASC)
                                .on("targetId", Direction.ASC)
                                .unique());
        mongo.indexOps(UserBlock.class).createIndex(new Index().on("targetId", Direction.ASC));
        mongo.indexOps(FollowRequest.class).createIndex(new Index().on("targetId", Direction.ASC));
        mongo.indexOps(SavedPost.class)
                .createIndex(
                        new Index().on("userId", Direction.ASC).on("createdAt", Direction.DESC));
        mongo.indexOps(ContentReport.class)
                .createIndex(
                        new Index().on("status", Direction.ASC).on("createdAt", Direction.ASC));
        mongo.indexOps(PostRevision.class)
                .createIndex(
                        new Index().on("postId", Direction.ASC).on("createdAt", Direction.DESC));
        mongo.indexOps(Story.class)
                .createIndex(
                        new Index().on("expiresAt", Direction.ASC).expire(java.time.Duration.ZERO));
        mongo.indexOps(StoryView.class)
                .createIndex(
                        new Index()
                                .on("storyId", Direction.ASC)
                                .on("userId", Direction.ASC)
                                .unique());
        mongo.indexOps(StoryReply.class)
                .createIndex(
                        new Index().on("storyId", Direction.ASC).on("createdAt", Direction.ASC));
        mongo.indexOps(StoryReaction.class)
                .createIndex(
                        new Index()
                                .on("storyId", Direction.ASC)
                                .on("userId", Direction.ASC)
                                .named("story_reaction_unique")
                                .unique());
        mongo.indexOps(CommentReaction.class)
                .createIndex(
                        new Index()
                                .on("commentId", Direction.ASC)
                                .on("userId", Direction.ASC)
                                .named("comment_reaction_unique")
                                .unique());
        mongo.indexOps(Post.class).createIndex(new Index().on("hashtags", Direction.ASC));
        mongo.indexOps(Post.class).createIndex(new Index().on("mentions", Direction.ASC));
    }
}
