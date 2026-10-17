-- Lock table for ShedLock (JdbcTemplate provider). Scheduled maintenance jobs take a row here
-- so that only one application replica runs a given job at a time.
-- The table is created unqualified, i.e. in Flyway's default schema (DB_SCHEMA), which is the
-- same schema SchedulerLockConfig points the lock provider at.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
