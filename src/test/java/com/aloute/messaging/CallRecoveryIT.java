package com.aloute.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.ForbiddenException;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.messaging.api.*;
import com.aloute.messaging.calls.application.*;
import com.aloute.messaging.calls.domain.CallSessions;
import com.aloute.messaging.calls.infrastructure.JdbcCallSessions;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.*;
import com.aloute.notifications.application.NotificationOutboxApplicationService;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.repository.*;
import com.aloute.service.*;
import com.aloute.users.api.CallParticipants;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real PostgreSQL/JPA/JDBC transactions. Refuses all databases except the named disposable local DB. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallRecoveryIT {
    private AnnotationConfigApplicationContext context;
    private LocalContainerEntityManagerFactoryBean factory;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private CallSessions sessions;
    private MissedCallHistory history;
    private CallCompletionService completion;
    private final SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
    private final AtomicBoolean failEnqueue = new AtomicBoolean();

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @BeforeAll
    void start() {
        String url = System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url != null, "Disposable PostgreSQL not configured");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aloute_quality_[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Refusing non-disposable/local database");
        dataSource = new DriverManagerDataSource(url, System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"),
                System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.aloute.model.entity");
        factory.setPersistenceUnitName("calls-recovery");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, JdbcCallSessions.class, CallCompletionService.class, DurableCallHistoryAuthorization.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> new JpaTransactionManager(factory.getObject()));
        context.registerBean(PostgresDocumentStore.class, () -> new PostgresDocumentStore(jdbc, new ObjectMapper().findAndRegisterModules()));
        context.registerBean(ChatService.class, this::actualHistoryService);
        var repo = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()))
                .getRepository(INotificationOutboxRepository.class);
        context.registerBean(NotificationOutboxApplicationService.class, () -> new NotificationOutboxApplicationService(repo));
        // Failure injection remains inside the real PostgreSQL transaction, after history was inserted.
        context.registerBean("testIntents", NotificationIntents.class, () -> (receiver, sender, type, reference, content) -> {
            if (failEnqueue.get()) throw new IllegalStateException("injected failure before outbox write");
            context.getBean(NotificationOutboxApplicationService.class).enqueue(receiver, sender, type, reference, content);
        }, definition -> definition.setPrimary(true));
        context.refresh();
        sessions = context.getBean(CallSessions.class);
        history = context.getBean(MissedCallHistory.class);
        completion = context.getBean(CallCompletionService.class);
    }

    @AfterAll
    void close() { if (context != null) context.close(); if (factory != null) factory.destroy(); }

    @BeforeEach
    void resetDoubles() {
        reset(ws); failEnqueue.set(false);
        // Isolate synthetic leftovers from earlier runs in this explicitly named disposable database.
        jdbc.update("UPDATE call_sessions SET processing_status='COMPLETED',state=CASE WHEN state IN ('RINGING','ACTIVE') THEN 'CANCELLED' ELSE state END WHERE call_id LIKE 'it-%'");
    }

    @Test
    void timeoutCommitsHistoryOutboxAndCompletionBeforeAnyNetworkDelivery() throws Exception {
        String id = expired();
        doAnswer(invocation -> {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "SELECT processing_status FROM call_sessions WHERE call_id=?")) {
                statement.setString(1, id);
                try (var result = statement.executeQuery()) { assertTrue(result.next()); assertEquals("COMPLETED", result.getString(1)); }
            }
            return null;
        }).when(ws).convertAndSendToUser(anyString(), anyString(), any());
        registry().processDistributedTimeouts();
        assertCompleted(id);
    }

    @Test
    void restartAfterClaimBeforeHistoryRecoversExpiredLease() {
        String id = expired(); sessions.expireDue();
        CallSessions.Claim orphan = sessions.claimDue().stream().filter(c -> c.call().callId().equals(id)).findFirst().orElseThrow();
        assertEquals(0, historyCount(id));
        jdbc.update("UPDATE call_sessions SET lease_until=now()-interval '1 second' WHERE call_id=?", id);
        registry().processDistributedTimeouts();
        assertCompleted(id);
        // A stale process cannot complete an already recovered lease a second time.
        assertTrue(completion.complete(orphan).isEmpty());
        assertCompleted(id);
    }

    @Test
    void failureAfterHistoryBeforeOutboxRollsEverythingBackAndRetries() {
        String id = expired(); failEnqueue.set(true);
        registry().processDistributedTimeouts();
        assertEquals(0, historyCount(id)); assertEquals(0, outboxCount(id));
        assertEquals("RETRY", value(id, "processing_status"));
        verifyNoInteractions(ws);
        failEnqueue.set(false);
        jdbc.update("UPDATE call_sessions SET next_attempt_at=now()-interval '1 second' WHERE call_id=?", id);
        registry().processDistributedTimeouts(); assertCompleted(id);
    }

    @Test
    void crashAfterCommitBeforeWebsocketDoesNotDuplicateOnRestart() {
        String id = expired(); sessions.expireDue();
        var claim = sessions.claimDue().stream().filter(c -> c.call().callId().equals(id)).findFirst().orElseThrow();
        doThrow(new IllegalStateException("injected websocket outage")).when(ws).convertAndSendToUser(anyString(), anyString(), any());
        assertTrue(completion.complete(claim).isPresent());
        registry().processDistributedTimeouts(); assertCompleted(id);
    }

    @Test
    void twoWorkersClaimAndCompleteOnlyOnce() throws Exception {
        String id = expired();
        concurrent(() -> { registry().processDistributedTimeouts(); return true; },
                () -> { registry().processDistributedTimeouts(); return true; });
        assertCompleted(id);
    }

    @Test
    void twoHistoryRequestsUseDatabaseUniquenessAndReturnCanonicalId() throws Exception {
        String id = expired(); sessions.expireDue();
        var results = concurrent(() -> history.createMissedCallMessage(1L, 2L, id, "audio"),
                () -> history.createMissedCallMessage(1L, 2L, id, "audio"));
        assertEquals(((ChatMessageResponse) results.get(0)).getId(), ((ChatMessageResponse) results.get(1)).getId());
        assertEquals(1, historyCount(id));
        assertThrows(ForbiddenException.class, () -> history.createMissedCallMessage(3L, 2L, id, "audio"));
        registry().processDistributedTimeouts(); assertCompleted(id);
    }

    @Test
    void ringingAndGuessedCallsCannotCreateHistory() {
        String id = fresh();
        assertThrows(ForbiddenException.class, () -> history.createMissedCallMessage(1L, 2L, id, "audio"));
        assertThrows(ForbiddenException.class, () -> history.createMissedCallMessage(1L, 2L, "guessed-" + id, "audio"));
        assertEquals(0, historyCount(id));
        assertFalse(sessions.register(id, 3L, 2L, "audio"));
        assertFalse(sessions.answer(id, 3L, 1L));
        assertTrue(sessions.finish(id, 3L, 2L).isEmpty());
    }

    @Test
    void callerCancellationCalleeDeclineAndActiveHangupNeverBecomeMissed() {
        String cancelled = fresh(); assertEquals("CANCELLED", sessions.finish(cancelled, 1L, 2L).orElseThrow().state());
        String declined = fresh(); assertEquals("DECLINED", sessions.finish(declined, 2L, 1L).orElseThrow().state());
        String ended = fresh(); assertTrue(sessions.answer(ended, 2L, 1L));
        assertEquals("ENDED", sessions.finish(ended, 1L, 2L).orElseThrow().state());
        registry().processDistributedTimeouts();
        for (String id : List.of(cancelled, declined, ended)) {
            assertEquals(0, historyCount(id)); assertEquals(0, outboxCount(id));
            assertFalse(sessions.answer(id, 2L, 1L)); assertFalse(sessions.register(id, 1L, 2L, "audio"));
            assertTrue(sessions.finish(id, 1L, 2L).isEmpty());
        }
    }

    @Test
    void answerVersusTimeoutAndHangupVersusTimeoutHaveOneTerminalOutcome() throws Exception {
        String id = expired();
        concurrent(() -> sessions.answer(id, 2L, 1L), () -> { registry().processDistributedTimeouts(); return true; });
        assertEquals("MISSED", value(id, "state")); assertCompleted(id);
        String other = expired();
        concurrent(() -> sessions.finish(other, 1L, 2L), () -> { registry().processDistributedTimeouts(); return true; });
        registry().processDistributedTimeouts(); assertCompleted(other);
        String active = fresh(); assertTrue(sessions.answer(active, 2L, 1L));
        jdbc.update("UPDATE call_sessions SET deadline_at=now()-interval '1 second' WHERE call_id=?", active);
        registry().processDistributedTimeouts(); assertEquals("ACTIVE", value(active, "state"));
        assertEquals(0, historyCount(active));
    }

    @Test
    void exhaustedOrCrashedLastAttemptRemainsVisibleAsFailed() {
        String id = expired(); sessions.expireDue();
        jdbc.update("UPDATE call_sessions SET processing_status='PROCESSING',attempt_count=20,lease_until=now()-interval '1 second' WHERE call_id=?", id);
        registry().processDistributedTimeouts();
        assertEquals("FAILED", value(id, "processing_status"));
        assertEquals(0, historyCount(id));
    }

    @Test
    void databaseEndpointOutageKeepsTheDurableIntentForARecoveredWorker() {
        String id = expired();
        var unavailable = new JdbcCallSessions(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:55433/aloute_quality_unavailable?connectTimeout=1", "aloute_test", "test-only")));
        var offline = new CallRegistryService(mock(ScheduledExecutorService.class), ws, ids -> Map.of(),
                mock(Notifications.class), history, null, false, unavailable, completion);
        assertThrows(org.springframework.dao.DataAccessException.class, offline::processDistributedTimeouts);
        assertEquals("RINGING", value(id, "state"));
        registry().processDistributedTimeouts(); assertCompleted(id);
    }

    @Test
    void actualRedisMirrorLossAndUnavailableRedisCannotLosePostgresHistory() {
        String configured = System.getenv("ALOUTE_REDIS_VALIDATION_PORT");
        Assumptions.assumeTrue(configured != null, "Disposable Redis not configured");
        var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(java.time.Duration.ofMillis(300)).shutdownTimeout(java.time.Duration.ofMillis(100)).build();
        var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(configured)), client);
        factory.afterPropertiesSet();
        try {
            var redis = new org.springframework.data.redis.core.StringRedisTemplate(factory);
            CallParticipants participants = ids -> Map.of(1L,new CallParticipants.Participant(1L,"test-1",null,null),
                    2L,new CallParticipants.Participant(2L,"test-2",null,null));
            var live = new CallRegistryService(mock(ScheduledExecutorService.class), ws, participants,
                    mock(Notifications.class), history, redis, true, sessions, completion);
            String id = "it-"+UUID.randomUUID(); assertTrue(live.registerOffer(id,1L,2L,"audio"));
            String key = "aloute:calls:state:"+Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertNotNull(redis.opsForValue().get(key));
            redis.delete(key);
            assertTrue(live.findCall(id).isPresent());
            jdbc.update("UPDATE call_sessions SET deadline_at=now()-interval '1 second' WHERE call_id=?",id);
            live.processDistributedTimeouts(); assertCompleted(id);

            var missingFactory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                    new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",56380),client);
            missingFactory.afterPropertiesSet();
            try {
                var offline = new CallRegistryService(mock(ScheduledExecutorService.class),ws,participants,
                        mock(Notifications.class),history,new org.springframework.data.redis.core.StringRedisTemplate(missingFactory),true,sessions,completion);
                String other="it-"+UUID.randomUUID(); assertTrue(offline.registerOffer(other,1L,2L,"audio"));
                jdbc.update("UPDATE call_sessions SET deadline_at=now()-interval '1 second' WHERE call_id=?",other);
                offline.processDistributedTimeouts(); assertCompleted(other);
            } finally { missingFactory.destroy(); }
        } finally { factory.destroy(); }
    }

    @Test
    void activeCallsExpireWithoutMisclassifyingThemAsMissed() {
        String id=fresh(); assertTrue(sessions.answer(id,2L,1L));
        jdbc.update("UPDATE call_sessions SET answered_at=now()-interval '3 hours' WHERE call_id=?",id);
        registry().processDistributedTimeouts();
        assertEquals("ENDED",value(id,"state")); assertEquals(0,historyCount(id)); assertEquals(0,outboxCount(id));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"claim-only","crash-after-history","crash-after-commit"})
    void processExitAndTwoIndependentJvmWorkersRecoverExactlyOneCompletion(String mode) throws Exception {
        String id=expired();
        Process crashed=startWorker(mode, "claim-"+mode);
        try {
            assertTrue(crashed.waitFor(50,TimeUnit.SECONDS));
            assertEquals(mode.equals("crash-after-history")?42:mode.equals("crash-after-commit")?43:0,crashed.exitValue());
        } finally { if(crashed.isAlive()) crashed.destroyForcibly(); }
        boolean committed=mode.equals("crash-after-commit");
        assertEquals(committed?"COMPLETED":"PROCESSING",value(id,"processing_status"));
        assertEquals(committed?1:0,historyCount(id)); assertEquals(committed?1:0,outboxCount(id));
        jdbc.update("UPDATE call_sessions SET lease_until=now()-interval '1 second' WHERE call_id=? AND processing_status='PROCESSING'",id);
        Process first=startWorker("complete", "one-"+mode), second=startWorker("complete", "two-"+mode);
        try {
            assertTrue(first.waitFor(50,TimeUnit.SECONDS)); assertTrue(second.waitFor(50,TimeUnit.SECONDS));
            assertEquals(0,first.exitValue()); assertEquals(0,second.exitValue()); assertCompleted(id);
        } finally { if(first.isAlive()) first.destroyForcibly(); if(second.isAlive()) second.destroyForcibly(); }
    }

    private Process startWorker(String mode,String suffix) throws Exception {
        var args=new java.util.ArrayList<String>(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-XX:ActiveProcessorCount=1", "-Duser.timezone=UTC",
                "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),
                "-javaagent:"+java.nio.file.Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),CallRecoveryIT.class.getName(),mode));
        var file=java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"),"call-worker-",".args");
        String encoded=args.stream().map(value -> "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"")
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        java.nio.file.Files.writeString(file,encoded,java.nio.charset.StandardCharsets.UTF_8);
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        return new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin",executable).toString(),"@"+file.toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("target/quality-call-worker-"+suffix+".log").toFile()).start();
    }

    public static void main(String[] arguments) {
        CallRecoveryIT worker=new CallRecoveryIT();
        try {
            worker.start();
            if("claim-only".equals(arguments[0])) { worker.sessions.expireDue(); worker.sessions.claimDue(); }
            else if("crash-after-history".equals(arguments[0])) {
                worker.sessions.expireDue(); var claim=worker.sessions.claimDue().getFirst();
                new org.springframework.transaction.support.TransactionTemplate(worker.context.getBean(PlatformTransactionManager.class))
                        .execute(status -> {
                            var call=worker.sessions.lockClaim(claim).orElseThrow();
                            worker.history.createMissedCallMessage(call.callerId(),call.calleeId(),call.callId(),call.callType());
                            Runtime.getRuntime().halt(42); // only this dedicated test JVM, with an uncommitted DB write
                            return null;
                        });
            } else if("crash-after-commit".equals(arguments[0])) {
                worker.sessions.expireDue(); worker.completion.complete(worker.sessions.claimDue().getFirst());
                Runtime.getRuntime().halt(43); // PostgreSQL committed; timeout signaling has not run
            } else worker.registry().processDistributedTimeouts();
        } finally { worker.close(); }
    }

    private ChatService actualHistoryService() {
        var documents = context.getBean(PostgresDocumentStore.class);
        var users = mock(IUserRepository.class);
        for (long n = 1; n <= 3; n++) { User user = new User(); user.setId(n); user.setUsername("test-"+n);
            when(users.findById(n)).thenReturn(Optional.of(user)); }
        return new ChatService(new IChatMessageRepositoryImpl(documents), users, mock(IGroupReadStatusRepository.class),
                mock(GroupService.class), ws, mock(FriendService.class), documents, mock(ContentAccessService.class),
                mock(PushNotificationService.class), mock(ConversationService.class), new ChatMessageMapper(users),
                mock(ChatHistoryService.class), null, null, context.getBean(CallHistoryAuthorization.class));
    }

    private CallRegistryService registry() {
        CallParticipants participants = ids -> Map.of(1L, new CallParticipants.Participant(1L,"test-1",null,null),
                2L, new CallParticipants.Participant(2L,"test-2",null,null));
        return new CallRegistryService(mock(ScheduledExecutorService.class), ws, participants, mock(Notifications.class),
                history, null, false, sessions, completion);
    }
    private String fresh() { String id = "it-"+UUID.randomUUID(); assertTrue(sessions.register(id,1L,2L,"audio")); return id; }
    private String expired() { String id=fresh(); jdbc.update("UPDATE call_sessions SET deadline_at=now()-interval '1 second' WHERE call_id=?",id); return id; }
    private String value(String id,String column) {
        if (!List.of("processing_status","state").contains(column)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT "+column+" FROM call_sessions WHERE call_id=?",String.class,id);
    }
    private int historyCount(String id) { return jdbc.queryForObject("SELECT count(*) FROM app_document_records WHERE collection_name='chat_messages' AND payload->>'callId'=?",Integer.class,id); }
    private int outboxCount(String id) { return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE reference_id=?",Integer.class,id); }
    private void assertCompleted(String id) { assertEquals("COMPLETED",value(id,"processing_status")); assertEquals(1,historyCount(id)); assertEquals(1,outboxCount(id)); }
    private List<Object> concurrent(Callable<?> first,Callable<?> second) throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> { start.await(); return first.call(); });
            var b=executor.submit(() -> { start.await(); return second.call(); });
            start.countDown(); return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
    }
}
