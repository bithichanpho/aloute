package com.aloute.service;

import com.aloute.model.document.FollowRequest;
import com.aloute.model.document.UserBlock;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.IFriendRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reconciles relationship state between the PostgreSQL JSONB document store and relational JPA tables.
 * UserBlock is the persisted source of truth for a block; a reciprocal Friend
 * edge is the source of truth for an accepted follow request.
 */
@Service
public class PostgresRelationshipReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PostgresRelationshipReconciliationService.class);
    private static final int BATCH_SIZE = 250;

    private final PostgresDocumentStore documentStore;
    private final IFriendRepository friends;
    private final ICloseFriendRepository closeFriends;

    public PostgresRelationshipReconciliationService(
            PostgresDocumentStore documentStore,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends) {
        this.documentStore = documentStore;
        this.friends = friends;
        this.closeFriends = closeFriends;
    }

    @Scheduled(
            fixedDelayString = "${app.maintenance.relationship-reconciliation-ms:900000}",
            initialDelayString = "${app.maintenance.relationship-reconciliation-initial-ms:60000}")
    @SchedulerLock(name = "postgresRelationshipReconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reconcileRelationships() {
        reconcileBlocksInBatches();
        reconcileAcceptedFollowRequestsInBatches();
    }

    private void reconcileBlocksInBatches() {
        String lastId = null;
        while (true) {
            Query query = Query.query(Criteria.where("relationshipsSynced").ne(true))
                    .with(Sort.by(Sort.Direction.ASC, "id")).limit(BATCH_SIZE);
            if (lastId != null) query.addCriteria(Criteria.where("id").gt(lastId));
            final java.util.List<UserBlock> batch;
            try {
                batch = documentStore.find(query, UserBlock.class);
            } catch (RuntimeException ex) {
                log.warn("Could not scan user blocks for PostgreSQL relationship reconciliation ({})",
                        ex.getClass().getSimpleName());
                return;
            }
            if (batch.isEmpty()) return;
            for (UserBlock block : batch) {
                try {
                    removeRelationshipEdges(block.getOwnerId(), block.getTargetId());
                    documentStore.updateFirst(
                            Query.query(Criteria.where("id").is(block.getId())),
                            new com.aloute.persistence.postgres.document.Update().set("relationshipsSynced", true),
                            UserBlock.class);
                } catch (RuntimeException ex) {
                    log.warn("Relationship cleanup pending for block {}:{} ({})",
                            block.getOwnerId(), block.getTargetId(), ex.getClass().getSimpleName());
                }
            }
            String nextId = batch.get(batch.size() - 1).getId();
            if (nextId == null || nextId.equals(lastId) || batch.size() < BATCH_SIZE) return;
            lastId = nextId;
        }
    }

    private void reconcileAcceptedFollowRequestsInBatches() {
        String lastId = null;
        while (true) {
            Query query = new Query().with(Sort.by(Sort.Direction.ASC, "id")).limit(BATCH_SIZE);
            if (lastId != null) query.addCriteria(Criteria.where("id").gt(lastId));
            final java.util.List<FollowRequest> batch;
            try {
                batch = documentStore.find(query, FollowRequest.class);
            } catch (RuntimeException ex) {
                log.warn("Could not scan follow requests for PostgreSQL relationship reconciliation ({})",
                        ex.getClass().getSimpleName());
                return;
            }
            if (batch.isEmpty()) return;
            for (FollowRequest request : batch) {
                try {
                    if (friends.existsByFollowerIdAndFollowedId(
                            request.getFollowerId(), request.getTargetId())) {
                        documentStore.remove(Query.query(Criteria.where("id").is(request.getId())), FollowRequest.class);
                    }
                } catch (RuntimeException ex) {
                    log.warn("Follow-request cleanup pending for request {} ({})",
                            request.getId(), ex.getClass().getSimpleName());
                }
            }
            String nextId = batch.get(batch.size() - 1).getId();
            if (nextId == null || nextId.equals(lastId) || batch.size() < BATCH_SIZE) return;
            lastId = nextId;
        }
    }

    private void removeRelationshipEdges(Long owner, Long target) {
        closeFriends.findByRequesterIdAndReceiverId(owner, target).ifPresent(closeFriends::delete);
        closeFriends.findByRequesterIdAndReceiverId(target, owner).ifPresent(closeFriends::delete);
        friends.findByFollowerIdAndFollowedId(owner, target).ifPresent(friends::delete);
        friends.findByFollowerIdAndFollowedId(target, owner).ifPresent(friends::delete);
    }
}
