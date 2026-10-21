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

-- A RUNNING run left by a process restart is closed at startup as FAILED with
-- failure_reason='应用重启时评测运行仍未完成'; its RUNNING cases become UNDETERMINED.
