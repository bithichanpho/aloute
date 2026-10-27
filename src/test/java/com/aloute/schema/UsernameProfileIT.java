package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.document.*;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import com.aloute.service.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Existing username persistence, same IDs and privacy rules, on a disposable local database. */
class UsernameProfileIT {
    @Test void handlesResolveExistingIdentitiesAndNeverBypassRevokedPrivateAccess() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().load().migrate();
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(database.source);
            factory.setPackagesToScan("com.aloute.model.entity"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            var settings = new Properties(); settings.put("hibernate.hbm2ddl.auto", "validate");
            settings.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            factory.setJpaProperties(settings); factory.afterPropertiesSet();
            try (var entityManager = factory.getObject().createEntityManager()) {
                var repositories = new JpaRepositoryFactory(entityManager);
                var users = repositories.getRepository(IUserRepository.class); var follows = repositories.getRepository(IFriendRepository.class);
                var store = new PostgresDocumentStore(database.jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
                var access = new ContentAccessService(store, users, follows, repositories.getRepository(ICommunityRepository.class), repositories.getRepository(ICommunityMemberRepository.class));
                var service = new UserService(users, follows, mock(FriendService.class), mock(CloseFriendService.class), mock(SimpMessagingTemplate.class), access);
                Long role = database.jdbc.queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id", Long.class);
                Long owner = user(database, role, "caso.name"); Long viewer = user(database, role, "viewer");
                Long numeric = user(database, role, "123"); Long legacy = user(database, role, "legacy@example.com");
                assertEquals(owner, service.getProfileByUsername("CaSo.NaMe", viewer).getId());
                assertEquals(numeric, service.getProfileByUsername("123", viewer).getId());
                assertEquals(legacy, service.getProfileByUsername("LEGACY@example.com", viewer).getId());
                assertEquals(owner, service.getProfile(owner, viewer).getId());
                assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername("missing", viewer));
                var post = new Post(); post.setId("private-post"); post.setUserId(owner.toString()); post.setContent("Test-only private content");
                assertFalse(access.canRead(post, viewer));
                database.jdbc.update("INSERT INTO friends(follower_id,followed_id) VALUES (?,?)", viewer, owner);
                assertTrue(service.getProfileByUsername("caso.name", viewer).isFollowedByCurrentUser()); assertTrue(access.canRead(post, viewer));
                database.jdbc.update("DELETE FROM friends WHERE follower_id=? AND followed_id=?", viewer, owner);
                assertFalse(service.getProfileByUsername("caso.name", viewer).isFollowedByCurrentUser()); assertFalse(access.canRead(post, viewer));
                var block = new UserBlock(); block.setId(owner + ":" + viewer); block.setOwnerId(owner); block.setTargetId(viewer); store.save(block);
                assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername("caso.name", viewer));
                assertThrows(ResourceNotFoundException.class, () -> service.getProfile(owner, viewer));
                store.remove(Query.query(Criteria.where("_id").is(block.getId())), UserBlock.class);
                database.jdbc.update("UPDATE users SET is_active=false WHERE id=?", owner); entityManager.clear();
                assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername("caso.name", viewer));
                assertEquals(4, database.jdbc.queryForObject("SELECT count(*) FROM users WHERE username IN ('caso.name','viewer','123','legacy@example.com')", Integer.class));
            } finally { factory.destroy(); }
        }
    }
    private Long user(DisposablePostgres database, Long role, String username) {
        return database.jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,is_private,role_id) VALUES (?,?, 'test-only',?,true,?) RETURNING id",
                Long.class, username, username + ".test@example.invalid", username, role);
    }
}
