package com.aloute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Bật tường minh Mongo repositories + auditing.
 * spring-boot-starter-data-mongodb đã tự auto-config MongoTemplate từ
 * spring.data.mongodb.uri (xem application-mongo.yml), class này chỉ khai báo
 * rõ ràng package repository và bật auto-index-creation cho các annotation
 * @Indexed / @CompoundIndex trong Like.java, ChatMessage.java (unique index
 * "post_user_unique" để 1 user không like trùng 1 bài viết nhiều lần).
 */
@Configuration
@EnableMongoRepositories(basePackages = "com.aloute.repository")
@EnableMongoAuditing
public class MongoConfig {
}