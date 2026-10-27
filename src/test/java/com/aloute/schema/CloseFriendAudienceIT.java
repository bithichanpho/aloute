package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.aloute.model.document.*;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.repository.*;
import com.aloute.service.ContentAccessService;
import com.aloute.social.infrastructure.JpaCloseFriendships;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Real JSONB persistence and JPA relationships in an owned disposable local database only. */
class CloseFriendAudienceIT {
    @Test void persistedCloseFriendAudienceUsesCurrentAcceptedMutualRelationships() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().load().migrate();
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(database.source); factory.setPackagesToScan("com.aloute.model.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            var properties = new Properties();
            properties.put("hibernate.hbm2ddl.auto","validate");
            properties.put("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            properties.put("hibernate.jdbc.time_zone","UTC"); factory.setJpaProperties(properties);
            factory.afterPropertiesSet();
            try (var entityManager = factory.getObject().createEntityManager()) {
                var repositories = new JpaRepositoryFactory(entityManager);
                var relationships = repositories.getRepository(ICloseFriendRepository.class);
                var store = new PostgresDocumentStore(database.jdbc,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
                var access = new ContentAccessService(store,repositories.getRepository(IUserRepository.class),
                        repositories.getRepository(IFriendRepository.class),repositories.getRepository(ICommunityRepository.class),
                        repositories.getRepository(ICommunityMemberRepository.class),new JpaCloseFriendships(relationships));
                var jdbc = database.jdbc;
                Long role = jdbc.queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id",Long.class);
                Long owner = jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,is_private,role_id) VALUES ('close-owner','owner@example.invalid','test-only','Owner',true,?) RETURNING id",Long.class,role);
                Long viewer = jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,role_id) VALUES ('close-viewer','viewer@example.invalid','test-only','Viewer',?) RETURNING id",Long.class,role);
                jdbc.update("INSERT INTO friends(follower_id,followed_id) VALUES (?,?),(?,?)",owner,viewer,viewer,owner);
                jdbc.update("INSERT INTO close_friends(requester_id,receiver_id,status,created_at) VALUES (?,?,'PENDING',now())",owner,viewer);
                var post = new Post(); post.setId("close-post"); post.setUserId(owner.toString());
                post.setContent("Disposable close-friend content"); post.setVisibility(PostVisibility.CLOSE_FRIENDS);
                post.setCreatedAt(java.time.LocalDateTime.now()); store.save(post);
                var loaded = store.findById("close-post",Post.class);
                assertEquals(PostVisibility.CLOSE_FRIENDS,loaded.getVisibility());
                assertFalse(access.canRead(loaded,viewer)); assertFalse(access.forPosts(List.of(loaded),viewer).canRead(loaded));
                jdbc.update("UPDATE close_friends SET status='ACCEPTED' WHERE requester_id=? AND receiver_id=?",owner,viewer);
                entityManager.clear();
                assertTrue(access.canRead(loaded,viewer)); assertTrue(access.forPosts(List.of(loaded),viewer).canRead(loaded));
                jdbc.update("DELETE FROM close_friends WHERE requester_id=? AND receiver_id=?",owner,viewer);
                entityManager.clear();
                assertFalse(access.canRead(loaded,viewer)); assertFalse(access.forPosts(List.of(loaded),viewer).canRead(loaded));
                jdbc.update("INSERT INTO close_friends(requester_id,receiver_id,status,created_at) VALUES (?,?,'ACCEPTED',now())",viewer,owner);
                entityManager.clear(); assertTrue(access.canRead(loaded,viewer));
                jdbc.update("DELETE FROM friends WHERE follower_id=? AND followed_id=?",owner,viewer);
                assertFalse(access.canRead(loaded,viewer)); assertFalse(access.forPosts(List.of(loaded),viewer).canRead(loaded));
                assertTrue(access.canRead(loaded,owner)); assertFalse(access.canRead(loaded,null));
            } finally { factory.destroy(); }
        }
    }
}
