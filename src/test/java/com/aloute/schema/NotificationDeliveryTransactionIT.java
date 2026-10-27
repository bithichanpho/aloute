package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.notifications.api.NotificationIntents;
import com.aloute.notifications.application.NotificationOutboxApplicationService;
import com.aloute.repository.INotificationOutboxRepository;
import java.time.LocalDateTime;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual JPA/JDBC commits on an owned PostgreSQL instance; no application credentials. */
class NotificationDeliveryTransactionIT {
    @Test
    void outboxRollsBackWithBusinessTransactionAndAfterCommitUsesANewTransaction() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().load().migrate();
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(database.source); factory.setPackagesToScan("com.aloute.model.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            var properties = new Properties();
            properties.setProperty("hibernate.hbm2ddl.auto", "validate");
            properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            properties.setProperty("hibernate.jdbc.time_zone", "UTC"); factory.setJpaProperties(properties);
            try {
                factory.afterPropertiesSet();
                var managerFactory = factory.getObject();
                var transactions = new JpaTransactionManager(managerFactory);
                transactions.setDataSource(database.source);
                var shared = SharedEntityManagerCreator.createSharedEntityManager(managerFactory);
                var repository = new JpaRepositoryFactory(shared).getRepository(INotificationOutboxRepository.class);
                var target = new NotificationOutboxApplicationService(repository);
                var interceptor = new TransactionInterceptor();
                interceptor.setTransactionManager(transactions);
                interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
                var proxy = new ProxyFactory(target); proxy.setInterfaces(NotificationIntents.class); proxy.addAdvice(interceptor);
                var outbox = (NotificationIntents) proxy.getProxy();
                var transaction = new TransactionTemplate(transactions);

                transaction.executeWithoutResult(status -> {
                    outbox.enqueue(2L, 1L, "FOLLOW", null, "rolled back");
                    status.setRollbackOnly();
                });
                assertEquals(0, database.jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class));
                transaction.executeWithoutResult(status -> TransactionCommitHooks.afterCommit(() -> {
                    assertTrue(TransactionCommitHooks.isExecutingAfterCommitCallback());
                    outbox.enqueueAfterCommit(2L, 1L, "FOLLOW", null, "committed callback");
                }));
                assertEquals(1, database.jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class));
                String id = database.jdbc.queryForObject("SELECT id FROM notification_outbox", String.class);
                LocalDateTime firstDelivery = LocalDateTime.of(2026, 10, 27, 8, 0);
                outbox.markInAppDelivered(id, firstDelivery);
                outbox.markInAppDelivered(id, firstDelivery.plusMinutes(1));
                assertEquals(firstDelivery, database.jdbc.queryForObject(
                        "SELECT in_app_delivered_at FROM notification_outbox WHERE id=?", LocalDateTime.class, id));
            } finally {
                factory.destroy();
            }
        }
    }
}
