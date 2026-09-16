package com.aloute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Bật tường minh Mongo repositories + auditing.
 * spring-boot-starter-data-mongodb đã tự auto-config MongoTemplate từ
 * spring.data.mongodb.uri (xem application-mongo.yml), class này chỉ khai báo
 * rõ ràng package repository và bật auditing cho @CreatedDate trong
 * Post/Comment/ChatMessage.
 *
 * KHÔNG khai báo MongoTransactionManager ở đây: transaction của Mongo đòi hỏi
 * replica set, và LikeService bản hợp nhất đã xử lý tranh chấp like/unlike
 * bằng unique index + thao tác nguyên tử nên không cần transaction.
 */
@Configuration
@EnableMongoRepositories(basePackages = "com.aloute.repository")
@EnableMongoAuditing
public class MongoConfig {
}
