-- ChainPilot Agent evaluation centre reference DDL (MySQL 8 / H2 compatible).
-- The runtime repository executes the same CREATE TABLE IF NOT EXISTS statements and
-- additive upgrades on startup, matching the existing supply-chain persistence pattern.
-- Existing installations are upgraded in place; no business table is dropped or rebuilt.

CREATE TABLE IF NOT EXISTS chainpilot_evaluation_run (
    run_id             VARCHAR(36) PRIMARY KEY,
    dataset_version    VARCHAR(64) NOT NULL,
    mode               VARCHAR(16) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    environment        VARCHAR(64) NOT NULL,
    started_at         BIGINT NOT NULL,
    completed_at       BIGINT,
    total_cases        INTEGER NOT NULL,
    passed_count       INTEGER NOT NULL,
    failed_count       INTEGER NOT NULL,
    undetermined_count INTEGER NOT NULL,
    duration_ms        BIGINT,
    metrics_json       TEXT,
    failure_reason     VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS chainpilot_evaluation_case_result (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id            VARCHAR(36) NOT NULL,
    case_id           VARCHAR(80) NOT NULL,
    case_name         VARCHAR(300) NOT NULL,
    scenario          VARCHAR(400) NOT NULL,
    category          VARCHAR(32) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    duration_ms       BIGINT NOT NULL,
    task_id           VARCHAR(36),
    assertions_json   TEXT,
    observation_json  TEXT,
    failure_type      VARCHAR(48),
    failure_message   VARCHAR(1000),
    sort_order        INTEGER NOT NULL,
    case_version      VARCHAR(64),
    task_mode         VARCHAR(16),
    CONSTRAINT fk_evaluation_result_run FOREIGN KEY (run_id)
        REFERENCES chainpilot_evaluation_run (run_id) ON DELETE CASCADE,
    CONSTRAINT uk_evaluation_result_run_case UNIQUE (run_id, case_id)
);

CREATE INDEX idx_evaluation_result_run ON chainpilot_evaluation_case_result (run_id);
CREATE INDEX idx_evaluation_run_status_started ON chainpilot_evaluation_run (status, started_at);
CREATE INDEX idx_evaluation_run_mode_started ON chainpilot_evaluation_run (mode, started_at);

-- AGENT evaluation lease lock (added 2026-10-22, lease/fencing design). Exactly one row named
-- AGENT_EVALUATION guards the single active AGENT run across JVMs. lease_expires_at and
-- updated_at are epoch milliseconds compared against the DATABASE clock
-- (SELECT CURRENT_TIMESTAMP), never the JVM clock, so skewed hosts cannot split ownership.
CREATE TABLE IF NOT EXISTS chainpilot_evaluation_lock (
    lock_name          VARCHAR(64) PRIMARY KEY,
    owner_instance_id  VARCHAR(64),
    lease_expires_at   BIGINT NOT NULL,
    fence_version      BIGINT NOT NULL,
    run_id             VARCHAR(36),
    updated_at         BIGINT NOT NULL
);

-- The runtime repository inserts this seed row when absent; run it once on a pre-created schema.
-- On MySQL 8 a repeated execution is safe with: INSERT IGNORE INTO ...
INSERT INTO chainpilot_evaluation_lock
    (lock_name, owner_instance_id, lease_expires_at, fence_version, run_id, updated_at)
    VALUES ('AGENT_EVALUATION', NULL, 0, 0, NULL, 0);

-- Upgrade from the first evaluation-centre schema (2026-10-21). The runtime repository
-- applies these additively and ignores duplicate-column/constraint errors:
-- ALTER TABLE chainpilot_evaluation_run ADD COLUMN failure_reason VARCHAR(1000);
-- ALTER TABLE chainpilot_evaluation_case_result ADD COLUMN case_version VARCHAR(64);
-- ALTER TABLE chainpilot_evaluation_case_result ADD COLUMN task_mode VARCHAR(16);
-- ALTER TABLE chainpilot_evaluation_case_result
--   ADD CONSTRAINT fk_evaluation_result_run FOREIGN KEY (run_id)
--   REFERENCES chainpilot_evaluation_run (run_id) ON DELETE CASCADE;
-- ALTER TABLE chainpilot_evaluation_case_result
--   ADD CONSTRAINT uk_evaluation_result_run_case UNIQUE (run_id, case_id);
-- CREATE INDEX idx_evaluation_run_status_started ON chainpilot_evaluation_run (status, started_at);
-- CREATE INDEX idx_evaluation_run_mode_started ON chainpilot_evaluation_run (mode, started_at);
-- See chainpilot-lease-ownership.sql for the guarded, re-runnable upgrade script.

-- Ownership semantics (replaces the former "close every RUNNING run at startup" behaviour):
-- an AGENT run is advanced and finalized only by the instance whose owner_instance_id and
-- fence_version still match the lock row. A lease that is not renewed expires and is taken over
-- atomically: the run becomes FAILED and its RUNNING cases UNDETERMINED/'INTERRUPTED' in the same
-- transaction that frees the lock. No run is re-executed automatically.
