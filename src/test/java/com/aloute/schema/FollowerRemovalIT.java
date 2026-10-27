package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.*;
import com.aloute.notifications.api.Notifications;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.repository.*;
import com.aloute.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises actual JPQL deletion and shared JPA/JDBC rollback only in a disposable local DB. */
class FollowerRemovalIT {
    @Test void atomicRemovalPreservesReverseEdgeRevokesPrivateReadsAndRollsBackWithRequest() throws Exception {
        try (var database = DisposablePostgres.create()) {
            database.migrations().load().migrate();
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(database.source); factory.setPackagesToScan("com.aloute.model.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            var properties = new Properties();
            properties.put("hibernate.hbm2ddl.auto", "validate");
            properties.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            factory.setJpaProperties(properties); factory.afterPropertiesSet();
            try {
                var manager = new JpaTransactionManager(factory.getObject());
                var transaction = new TransactionTemplate(manager);
                var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
                var repositories = new JpaRepositoryFactory(entityManager);
                var friends = repositories.getRepository(IFriendRepository.class);
                var users = repositories.getRepository(IUserRepository.class);
                var store = new PostgresDocumentStore(database.jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
                var access = new ContentAccessService(store, users, friends,
                        repositories.getRepository(ICommunityRepository.class), repositories.getRepository(ICommunityMemberRepository.class));
                var feeds = mock(FeedCacheInvalidator.class);
                var service = new FriendService(friends, mock(Notifications.class), access, store, users, feeds);
                var jdbc = database.jdbc;
                Long role = jdbc.queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id", Long.class);
                Long owner = user(database, role, "remove-owner", true);
                Long follower = user(database, role, "remove-viewer", false);
                Long other = user(database, role, "remove-other", false);
                jdbc.update("INSERT INTO friends(follower_id,followed_id) VALUES (?,?),(?,?),(?,?)", follower,owner, owner,follower, other,owner);
                var request = new FollowRequest(); request.setId(follower + ":" + owner); request.setFollowerId(follower); request.setTargetId(owner); store.save(request);
                var post = new Post(); post.setId("private-content"); post.setUserId(owner.toString()); post.setVisibility(PostVisibility.PUBLIC);
                post.setContent("Disposable private content"); post.setCreatedAt(java.time.LocalDateTime.now()); store.save(post);
                assertTrue(access.canRead(post, follower));
                var removed = transaction.execute(status -> service.removeFollower(owner, follower));
                assertTrue(removed.removed()); assertEquals(1, removed.followerCount());
                assertFalse(friends.existsByFollowerIdAndFollowedId(follower, owner));
                assertTrue(friends.existsByFollowerIdAndFollowedId(owner, follower));
                assertTrue(friends.existsByFollowerIdAndFollowedId(other, owner));
                assertNull(store.findById(request.getId(), FollowRequest.class));
                assertFalse(access.canRead(post, follower)); assertFalse(access.profileContentVisible(follower, owner));
                assertTrue(access.canRead(post, owner));
                assertFalse(transaction.execute(status -> service.removeFollower(owner, follower)).removed());
                assertEquals(Boolean.FALSE, transaction.execute(status -> service.toggleFollow(follower, owner)));
                assertNotNull(store.findById(request.getId(), FollowRequest.class));
                assertFalse(friends.existsByFollowerIdAndFollowedId(follower, owner));

                // A failing transaction must restore both the edge and its document request.
                jdbc.update("INSERT INTO friends(follower_id,followed_id) VALUES (?,?)", follower, owner);
                clearInvocations(feeds);
                assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
                    service.removeFollower(owner, follower); throw new IllegalStateException("rollback fixture");
                }));
                assertTrue(friends.existsByFollowerIdAndFollowedId(follower, owner));
                assertNotNull(store.findById(request.getId(), FollowRequest.class));
                verifyNoInteractions(feeds);

                // Concurrent duplicates remove at most one incoming edge, never recreate it.
                var gate = new CountDownLatch(1);
                try (var pool = Executors.newFixedThreadPool(2)) {
                    var tasks = new ArrayList<Future<Boolean>>();
                    for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                        return transaction.execute(status -> service.removeFollower(owner, follower)).removed();
                    }));
                    gate.countDown();
                    int changed = 0;
                    for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) changed++;
                    assertEquals(1, changed);
                }
                assertFalse(friends.existsByFollowerIdAndFollowedId(follower, owner));
                assertTrue(friends.existsByFollowerIdAndFollowedId(owner, follower));
                assertEquals(1, friends.countByFollowedId(owner));
            } finally { factory.destroy(); }
        }
    }

    private Long user(DisposablePostgres database, Long role, String username, boolean privateAccount) {
        return database.jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,is_private,role_id) VALUES (?,?, 'test-only',?,?,?) RETURNING id",
                Long.class, username, username + "@example.invalid", username, privateAccount, role);
    }
}
