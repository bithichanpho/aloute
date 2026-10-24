package com.aloute.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "app.db.relationship-migration.enabled",
        havingValue = "true")
/**
 * Legacy compatibility bridge for one-time local schema bootstrap only.
 * Production schema changes are tracked by Flyway; enable this explicitly with
 * ALOUTE_DB_RELATIONSHIP_MIGRATION=true only when bootstrapping an old local database.
 */
public class DatabaseRelationshipMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRelationshipMigrator.class);
    private static final String SCHEMA = "public";
    private static final long ADVISORY_LOCK_KEY = 726384913L;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DatabaseRelationshipMigrator(
            JdbcTemplate jdbcTemplate,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transactionTemplate.executeWithoutResult(status -> migrate());
    }

    private void migrate() {

        jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                        connection -> {
                            try (java.sql.PreparedStatement statement =
                                    connection.prepareStatement(
                                            "select pg_advisory_xact_lock(?)")) {
                                statement.setLong(1, ADVISORY_LOCK_KEY);
                                statement.execute();
                                return null;
                            }
                        });

        log.info("[ALOUTE] Checking PostgreSQL relationships/indexes...");
        validateExistingData();

        addForeignKey(
                "friends",
                "fk_friends_follower",
                "FOREIGN KEY (follower_id) REFERENCES public.users(id)",
                "select count(*) from public.friends f left join public.users u on"
                        + " u.id=f.follower_id where u.id is null");
        addForeignKey(
                "friends",
                "fk_friends_followed",
                "FOREIGN KEY (followed_id) REFERENCES public.users(id)",
                "select count(*) from public.friends f left join public.users u on"
                        + " u.id=f.followed_id where u.id is null");

        addForeignKey(
                "close_friends",
                "fk_close_friends_requester",
                "FOREIGN KEY (requester_id) REFERENCES public.users(id)",
                "select count(*) from public.close_friends f left join public.users u on"
                        + " u.id=f.requester_id where u.id is null");
        addForeignKey(
                "close_friends",
                "fk_close_friends_receiver",
                "FOREIGN KEY (receiver_id) REFERENCES public.users(id)",
                "select count(*) from public.close_friends f left join public.users u on"
                        + " u.id=f.receiver_id where u.id is null");

        addForeignKey(
                "notifications",
                "fk_notifications_receiver",
                "FOREIGN KEY (receiver_id) REFERENCES public.users(id)",
                "select count(*) from public.notifications n left join public.users u on"
                        + " u.id=n.receiver_id where u.id is null");
        addForeignKey(
                "notifications",
                "fk_notifications_sender",
                "FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE SET NULL",
                "select count(*) from public.notifications n left join public.users u on"
                        + " u.id=n.sender_id where n.sender_id is not null and u.id is null");

        addForeignKey(
                "chat_groups",
                "fk_chat_groups_creator",
                "FOREIGN KEY (created_by) REFERENCES public.users(id)",
                "select count(*) from public.chat_groups g left join public.users u on"
                        + " u.id=g.created_by where u.id is null");

        addForeignKey(
                "group_members",
                "fk_group_members_group",
                "FOREIGN KEY (group_id) REFERENCES public.chat_groups(id) ON DELETE CASCADE",
                "select count(*) from public.group_members gm left join public.chat_groups g on"
                        + " g.id=gm.group_id where g.id is null");
        addForeignKey(
                "group_members",
                "fk_group_members_user",
                "FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE",
                "select count(*) from public.group_members gm left join public.users u on"
                        + " u.id=gm.user_id where u.id is null");

        addForeignKey(
                "group_read_status",
                "fk_group_read_status_group",
                "FOREIGN KEY (group_id) REFERENCES public.chat_groups(id) ON DELETE CASCADE",
                "select count(*) from public.group_read_status r left join public.chat_groups g on"
                        + " g.id=r.group_id where g.id is null");
        addForeignKey(
                "group_read_status",
                "fk_group_read_status_user",
                "FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE",
                "select count(*) from public.group_read_status r left join public.users u on"
                        + " u.id=r.user_id where u.id is null");

        addForeignKey(
                "communities",
                "fk_communities_creator",
                "FOREIGN KEY (created_by) REFERENCES public.users(id)",
                "select count(*) from public.communities c left join public.users u on"
                        + " u.id=c.created_by where u.id is null");
        addForeignKey(
                "community_members",
                "fk_community_members_community",
                "FOREIGN KEY (community_id) REFERENCES public.communities(id) ON DELETE CASCADE",
                "select count(*) from public.community_members cm left join public.communities c on"
                        + " c.id=cm.community_id where c.id is null");
        addForeignKey(
                "community_members",
                "fk_community_members_user",
                "FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE",
                "select count(*) from public.community_members cm left join public.users u on"
                        + " u.id=cm.user_id where u.id is null");

        addUniqueConstraint(
                "friends", "uk_friends_follower_followed", "(follower_id, followed_id)");
        addUniqueConstraint(
                "close_friends",
                "uk_close_friends_requester_receiver",
                "(requester_id, receiver_id)");
        addUniqueConstraint("group_members", "uk_group_members_group_user", "(group_id, user_id)");
        addUniqueConstraint(
                "group_read_status", "uk_group_read_status_group_user", "(group_id, user_id)");
        addUniqueConstraint(
                "community_members",
                "uk_community_members_community_user",
                "(community_id, user_id)");

        addIndex("idx_friends_follower", "friends", "follower_id");
        addIndex("idx_friends_followed", "friends", "followed_id");
        addIndex("idx_close_friends_requester_status", "close_friends", "requester_id, status");
        addIndex("idx_close_friends_receiver_status", "close_friends", "receiver_id, status");
        addIndex(
                "idx_notifications_receiver_created",
                "notifications",
                "receiver_id, created_at DESC");
        addIndex("idx_notifications_sender", "notifications", "sender_id");
        addIndex("idx_chat_groups_created_by", "chat_groups", "created_by");
        addIndex("idx_group_members_group", "group_members", "group_id");
        addIndex("idx_group_members_user", "group_members", "user_id");
        addIndex("idx_group_read_status_group", "group_read_status", "group_id");
        addIndex("idx_group_read_status_user", "group_read_status", "user_id");
        addIndex("idx_communities_created_by", "communities", "created_by");
        addIndex("idx_community_members_community", "community_members", "community_id");
        addIndex("idx_community_members_user", "community_members", "user_id");
        addIndex(
                "idx_community_members_community_role_joined",
                "community_members",
                "community_id, role, joined_at");
        addIndex(
                "idx_communities_privacy_member_count",
                "communities",
                "privacy, member_count DESC");

        log.info(
                "[ALOUTE] PostgreSQL relationship migration completed. Existing data was"
                        + " preserved.");
    }

    private void validateExistingData() {
        logRelationshipDataIssue(
                "friends.follower_id orphan rows",
                "select count(*) from public.friends f left join public.users u on"
                        + " u.id=f.follower_id where u.id is null",
                "select f.id, f.follower_id from public.friends f left join public.users u on"
                        + " u.id=f.follower_id where u.id is null order by f.id limit 20");
        logRelationshipDataIssue(
                "friends.followed_id orphan rows",
                "select count(*) from public.friends f left join public.users u on"
                        + " u.id=f.followed_id where u.id is null",
                "select f.id, f.followed_id from public.friends f left join public.users u on"
                        + " u.id=f.followed_id where u.id is null order by f.id limit 20");

        logRelationshipDataIssue(
                "close_friends.requester_id orphan rows",
                "select count(*) from public.close_friends f left join public.users u on"
                        + " u.id=f.requester_id where u.id is null",
                null);
        logRelationshipDataIssue(
                "close_friends.receiver_id orphan rows",
                "select count(*) from public.close_friends f left join public.users u on"
                        + " u.id=f.receiver_id where u.id is null",
                null);

        logRelationshipDataIssue(
                "notifications.receiver_id orphan rows",
                "select count(*) from public.notifications n left join public.users u on"
                        + " u.id=n.receiver_id where u.id is null",
                null);
        logRelationshipDataIssue(
                "notifications.sender_id orphan rows",
                "select count(*) from public.notifications n left join public.users u on"
                        + " u.id=n.sender_id where n.sender_id is not null and u.id is null",
                null);

        logRelationshipDataIssue(
                "chat_groups.created_by orphan rows",
                "select count(*) from public.chat_groups g left join public.users u on"
                        + " u.id=g.created_by where u.id is null",
                null);

        logRelationshipDataIssue(
                "group_members.group_id orphan rows",
                "select count(*) from public.group_members gm left join public.chat_groups g on"
                        + " g.id=gm.group_id where g.id is null",
                null);
        logRelationshipDataIssue(
                "group_members.user_id orphan rows",
                "select count(*) from public.group_members gm left join public.users u on"
                        + " u.id=gm.user_id where u.id is null",
                null);

        logRelationshipDataIssue(
                "group_read_status.group_id orphan rows",
                "select count(*) from public.group_read_status r left join public.chat_groups g on"
                        + " g.id=r.group_id where g.id is null",
                null);
        logRelationshipDataIssue(
                "group_read_status.user_id orphan rows",
                "select count(*) from public.group_read_status r left join public.users u on"
                        + " u.id=r.user_id where u.id is null",
                null);

        logRelationshipDataIssue(
                "communities.created_by orphan rows",
                "select count(*) from public.communities c left join public.users u on"
                        + " u.id=c.created_by where u.id is null",
                null);
        logRelationshipDataIssue(
                "community_members.community_id orphan rows",
                "select count(*) from public.community_members cm left join public.communities c on"
                        + " c.id=cm.community_id where c.id is null",
                null);
        logRelationshipDataIssue(
                "community_members.user_id orphan rows",
                "select count(*) from public.community_members cm left join public.users u on"
                        + " u.id=cm.user_id where u.id is null",
                null);

        logDuplicateRelationship(
                "friends duplicate pairs",
                "select count(*) from (select follower_id, followed_id from public.friends group by"
                        + " 1,2 having count(*)>1) d");
        logDuplicateRelationship(
                "close_friends duplicate pairs",
                "select count(*) from (select requester_id, receiver_id from public.close_friends"
                        + " group by 1,2 having count(*)>1) d");
        logDuplicateRelationship(
                "group_members duplicate pairs",
                "select count(*) from (select group_id, user_id from public.group_members group by"
                        + " 1,2 having count(*)>1) d");
        logDuplicateRelationship(
                "group_read_status duplicate pairs",
                "select count(*) from (select group_id, user_id from public.group_read_status group"
                        + " by 1,2 having count(*)>1) d");
        logDuplicateRelationship(
                "community_members duplicate pairs",
                "select count(*) from (select community_id, user_id from public.community_members"
                        + " group by 1,2 having count(*)>1) d");
    }

    private void logRelationshipDataIssue(String label, String countSql, String sampleSql) {
        Long count = jdbcTemplate.queryForObject(countSql, Long.class);
        if (count != null && count > 0) {
            log.warn(
                    "[ALOUTE] {} = {}. Existing data will be preserved; related FK will be created"
                            + " as NOT VALID and will still enforce future writes.",
                    label,
                    count);
            if (sampleSql != null) {
                List<String> samples =
                        jdbcTemplate.query(
                                sampleSql, (rs, rowNum) -> rs.getLong(1) + "->" + rs.getLong(2));
                if (!samples.isEmpty()) {
                    log.warn("[ALOUTE] Sample legacy rows for {}: {}", label, samples);
                }
            }
        }
    }

    private void logDuplicateRelationship(String label, String sql) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        if (count != null && count > 0) {
            log.warn(
                    "[ALOUTE] {} = {}. UNIQUE constraint will be skipped for this relationship"
                            + " until duplicates are cleaned.",
                    label,
                    count);
        }
    }

    private void addForeignKey(
            String table, String constraintName, String definition, String orphanCheckSql) {
        if (!constraintExists(table, constraintName)) {
            jdbcTemplate.execute(
                    "alter table public."
                            + quoteIdentifier(table)
                            + " add constraint "
                            + quoteIdentifier(constraintName)
                            + " "
                            + definition
                            + " not valid");
            log.info(
                    "[ALOUTE] Added FK {}.{} as NOT VALID so existing legacy rows are preserved.",
                    table,
                    constraintName);
        }

        Long orphanCount = jdbcTemplate.queryForObject(orphanCheckSql, Long.class);
        if (orphanCount != null && orphanCount > 0) {
            log.warn(
                    "[ALOUTE] FK {}.{} remains NOT VALID because {} legacy orphan rows exist. No"
                            + " data was deleted.",
                    table,
                    constraintName,
                    orphanCount);
            return;
        }

        jdbcTemplate.execute(
                "alter table public."
                        + quoteIdentifier(table)
                        + " validate constraint "
                        + quoteIdentifier(constraintName));
        log.info("[ALOUTE] Validated FK {}.{}", table, constraintName);
    }

    private void addUniqueConstraint(String table, String constraintName, String columns) {
        if (constraintExists(table, constraintName)) {
            return;
        }

        String duplicateCheckSql = duplicateCheckSql(constraintName);
        if (duplicateCheckSql != null) {
            Long duplicateGroups = jdbcTemplate.queryForObject(duplicateCheckSql, Long.class);
            if (duplicateGroups != null && duplicateGroups > 0) {
                log.warn(
                        "[ALOUTE] UNIQUE {}.{} skipped because {} duplicate relationship groups"
                                + " already exist. Existing data was preserved.",
                        table,
                        constraintName,
                        duplicateGroups);
                return;
            }
        }

        jdbcTemplate.execute(
                "alter table public."
                        + quoteIdentifier(table)
                        + " add constraint "
                        + quoteIdentifier(constraintName)
                        + " unique "
                        + columns);
        log.info("[ALOUTE] Added UNIQUE {}.{}", table, constraintName);
    }

    private String duplicateCheckSql(String constraintName) {
        return switch (constraintName) {
            case "uk_friends_follower_followed" ->
                    "select count(*) from (select follower_id, followed_id from public.friends"
                            + " group by 1,2 having count(*)>1) d";
            case "uk_close_friends_requester_receiver" ->
                    "select count(*) from (select requester_id, receiver_id from"
                            + " public.close_friends group by 1,2 having count(*)>1) d";
            case "uk_group_members_group_user" ->
                    "select count(*) from (select group_id, user_id from public.group_members group"
                            + " by 1,2 having count(*)>1) d";
            case "uk_group_read_status_group_user" ->
                    "select count(*) from (select group_id, user_id from public.group_read_status"
                            + " group by 1,2 having count(*)>1) d";
            case "uk_community_members_community_user" ->
                    "select count(*) from (select community_id, user_id from"
                            + " public.community_members group by 1,2 having count(*)>1) d";
            default -> null;
        };
    }

    private void addIndex(String indexName, String table, String columns) {
        if (!indexExists(indexName)) {
            jdbcTemplate.execute(
                    "create index "
                            + quoteIdentifier(indexName)
                            + " on public."
                            + quoteIdentifier(table)
                            + " ("
                            + columns
                            + ")");
            log.info("[ALOUTE] Added index {}", indexName);
        }
    }

    private boolean constraintExists(String table, String constraintName) {
        Integer result =
                jdbcTemplate.queryForObject(
                        "select count(*) from pg_constraint c "
                                + "join pg_class t on t.oid=c.conrelid "
                                + "join pg_namespace n on n.oid=t.relnamespace "
                                + "where n.nspname=? and t.relname=? and c.conname=?",
                        Integer.class,
                        SCHEMA,
                        table,
                        constraintName);
        return result != null && result > 0;
    }

    private boolean indexExists(String indexName) {
        Integer result =
                jdbcTemplate.queryForObject(
                        "select count(*) from pg_indexes where schemaname=? and indexname=?",
                        Integer.class,
                        SCHEMA,
                        indexName);
        return result != null && result > 0;
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
