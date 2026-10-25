package com.aloute.messaging.calls.infrastructure;

import com.aloute.messaging.calls.domain.CallSessions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCallSessions implements CallSessions {
    private final JdbcTemplate jdbc;
    public JdbcCallSessions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean register(String id, Long caller, Long callee, String type) {
        jdbc.update("""
                INSERT INTO call_sessions(call_id,caller_id,callee_id,call_type,state,deadline_at)
                VALUES (?,?,?,?,'RINGING',statement_timestamp()+interval '30 seconds') ON CONFLICT DO NOTHING
                """, id, caller, callee, type);
        return jdbc.queryForObject("""
                SELECT caller_id=? AND callee_id=? AND call_type=? AND state IN ('RINGING','ACTIVE')
                    AND ((state='ACTIVE' AND answered_at>statement_timestamp()-interval '2 hours')
                        OR (state='RINGING' AND deadline_at>statement_timestamp())) FROM call_sessions WHERE call_id=?
                """, Boolean.class, caller, callee, type, id);
    }

    @Transactional
    public boolean answer(String id, Long sender, Long target) {
        // UPDATE predicates serialize answer/timeout/hangup on the same PostgreSQL row.
        return jdbc.update("""
                UPDATE call_sessions SET state='ACTIVE',answered_at=statement_timestamp()
                WHERE call_id=? AND callee_id=? AND caller_id=? AND state='RINGING'
                    AND deadline_at>statement_timestamp()
                """, id, sender, target) == 1;
    }

    public Optional<Snapshot> active(String id) {
        return jdbc.query("""
                SELECT * FROM call_sessions WHERE call_id=? AND
                    ((state='ACTIVE' AND answered_at>statement_timestamp()-interval '2 hours')
                    OR (state='RINGING' AND deadline_at>statement_timestamp()))
                """, this::snapshot, id).stream().findFirst();
    }

    @Transactional
    public Optional<Snapshot> finish(String id, Long actor, Long target) {
        return jdbc.query("""
                UPDATE call_sessions SET
                    state=CASE WHEN state='ACTIVE' THEN 'ENDED'
                        WHEN deadline_at<=statement_timestamp() THEN 'MISSED'
                        WHEN caller_id=? THEN 'CANCELLED' ELSE 'DECLINED' END,
                    processing_status=CASE WHEN state='RINGING' AND deadline_at<=statement_timestamp()
                        THEN 'PENDING' ELSE 'COMPLETED' END,
                    ended_at=statement_timestamp()
                WHERE call_id=? AND state IN ('RINGING','ACTIVE') AND
                    ((caller_id=? AND callee_id=?) OR (callee_id=? AND caller_id=?))
                RETURNING *
                """, this::snapshot, actor, id, actor, target, actor, target).stream().findFirst();
    }

    @Transactional
    public void expireDue() {
        jdbc.update("""
                WITH due AS (SELECT call_id FROM call_sessions WHERE state='ACTIVE'
                    AND answered_at<=statement_timestamp()-interval '2 hours'
                    ORDER BY answered_at,call_id LIMIT 100 FOR UPDATE SKIP LOCKED)
                UPDATE call_sessions c SET state='ENDED',ended_at=statement_timestamp()
                FROM due WHERE c.call_id=due.call_id AND c.state='ACTIVE'
                """);
        jdbc.update("""
                UPDATE call_sessions SET processing_status='FAILED',last_error='RecoveryAttemptsExhausted',
                    lease_until=NULL,lease_token=NULL WHERE processing_status='PROCESSING'
                    AND lease_until<=statement_timestamp() AND attempt_count>=20
                """);
        jdbc.update("""
                WITH due AS (SELECT call_id FROM call_sessions
                    WHERE state='RINGING' AND deadline_at<=statement_timestamp()
                    ORDER BY deadline_at,call_id LIMIT 100 FOR UPDATE SKIP LOCKED)
                UPDATE call_sessions c SET state='MISSED',ended_at=statement_timestamp(),processing_status='PENDING'
                FROM due WHERE c.call_id=due.call_id AND c.state='RINGING'
                """);
    }

    public boolean missedFor(String id, Long caller, Long callee) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM call_sessions WHERE call_id=? AND caller_id=? AND callee_id=?
                    AND state='MISSED')
                """, Boolean.class, id, caller, callee));
    }

    @Transactional
    public List<Claim> claimDue() {
        String token = UUID.randomUUID().toString();
        return jdbc.query("""
                WITH due AS (SELECT call_id FROM call_sessions WHERE state='MISSED' AND
                    ((processing_status IN ('PENDING','RETRY') AND next_attempt_at<=statement_timestamp())
                    OR (processing_status='PROCESSING' AND lease_until<=statement_timestamp()))
                    AND attempt_count<20 ORDER BY created_at,call_id LIMIT 10 FOR UPDATE SKIP LOCKED)
                UPDATE call_sessions c SET processing_status='PROCESSING',lease_token=?,
                    lease_until=statement_timestamp()+interval '60 seconds',attempt_count=attempt_count+1
                FROM due WHERE c.call_id=due.call_id RETURNING c.*
                """, (rs, n) -> new Claim(snapshot(rs, n), token), token);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Snapshot> lockClaim(Claim claim) {
        return jdbc.query("""
                SELECT * FROM call_sessions WHERE call_id=? AND lease_token=?
                    AND state='MISSED' AND processing_status='PROCESSING' FOR UPDATE
                """, this::snapshot, claim.call().callId(), claim.token()).stream().findFirst();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void completed(Claim claim) {
        jdbc.update("""
                UPDATE call_sessions SET processing_status='COMPLETED',lease_until=NULL,lease_token=NULL,
                    last_error=NULL WHERE call_id=? AND lease_token=? AND processing_status='PROCESSING'
                """, claim.call().callId(), claim.token());
    }

    @Transactional
    public void failed(Claim claim, String reason) {
        jdbc.update("""
                UPDATE call_sessions SET processing_status=CASE WHEN attempt_count>=20 THEN 'FAILED' ELSE 'RETRY' END,
                    last_error=?,lease_until=NULL,lease_token=NULL,
                    next_attempt_at=statement_timestamp()+make_interval(secs=>LEAST(3600,POWER(2,LEAST(attempt_count,12)))::int)
                WHERE call_id=? AND lease_token=? AND processing_status='PROCESSING'
                """, reason, claim.call().callId(), claim.token());
    }

    private Snapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new Snapshot(rs.getString("call_id"), rs.getLong("caller_id"), rs.getLong("callee_id"),
                rs.getString("call_type"), rs.getTimestamp("answered_at") != null,
                rs.getTimestamp("created_at").toInstant(), rs.getString("state"));
    }
}
