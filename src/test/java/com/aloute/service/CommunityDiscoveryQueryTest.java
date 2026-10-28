package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.ICommunityRepository;
import java.util.Collection;
import java.util.List;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class CommunityDiscoveryQueryTest {
    @Test
    void discoveryQueryCompilesAgainstTheRealEntityMappingsWithoutConnectingToAnyDatabase() throws Exception {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
                .build();
        try (var factory = new MetadataSources(registry).addAnnotatedClass(Community.class)
                .addAnnotatedClass(CommunityMember.class).addAnnotatedClass(User.class).addAnnotatedClass(Role.class)
                .buildMetadata().buildSessionFactory(); var session = factory.openSession()) {
            String hql = ICommunityRepository.class.getMethod("findDiscoverableForUser",
                    String.class, Long.class, Collection.class, Pageable.class).getAnnotation(Query.class).value();
            var query = session.createQuery(hql, Community.class).setParameter("privacy", "PUBLIC")
                    .setParameter("userId", 1L).setParameter("joinedRoles", List.of("OWNER", "MANAGER", "ADMIN", "MEMBER"))
                    .setMaxResults(30);
            assertEquals(Long.class, query.getParameter("userId").getParameterType());
            assertEquals(30, query.getMaxResults());
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
}
