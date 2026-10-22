-- ChainPilot task-ownership and evaluation-lease migration (MySQL 8).
--
-- Purpose: add the columns and the lock table that the lease/fencing design needs:
--   chainpilot_analysis_task.owner_instance_id / .lease_expires_at / .fence_version
--   chainpilot_evaluation_lock (one AGENT_EVALUATION row)
--
-- Applicability: MySQL 8 only (uses INFORMATION_SCHEMA + PREPARE). H2 installs are upgraded by
-- the runtime repositories at startup; do not run this file against the H2 demo database.
--
-- Repeatability: every statement is guarded by an INFORMATION_SCHEMA check, so the script can be
-- re-run safely. It is still additive only: no column, index, constraint or row is dropped.
--
-- Order of operations for an existing installation:
--   1. take the backup described in docs/DATABASE_RELEASE.md section 4 and stop the application;
--   2. run the PRE-FLIGHT snapshot, then this script;
--   3. run the POST-CHECK queries and confirm the column/table counts and the task status
--      distribution are unchanged except for the new nullable columns;
--   4. start ONE upgraded instance before scaling out.
--
-- Time semantics: lease_expires_at and updated_at store epoch milliseconds. The application
-- compares them against the database clock (SELECT CURRENT_TIMESTAMP) rather than the JVM clock,
-- so hosts with clock skew still agree on lease expiry.

-- ── PRE-FLIGHT (record the output in the change ticket) ─────────────────────────────────────
-- These probes must not touch chainpilot_analysis_task directly: on a fresh database the table is
-- created by step 1 below, and a plain SELECT there would abort the whole script.
SELECT DATABASE() AS target_schema, VERSION() AS mysql_version,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE() AND table_name = 'chainpilot_analysis_task') AS task_table_exists,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE() AND table_name = 'chainpilot_evaluation_lock') AS lock_table_exists;

-- For an EXISTING installation, additionally run this snapshot by hand before the upgrade and keep
-- the result; step 4 prints the same query again so the change ticket can compare them:
--   SELECT status, COUNT(*) AS rows_before FROM chainpilot_analysis_task GROUP BY status;

-- ── 1. Fresh installation: task table with the ownership columns already present ────────────
-- Mirrors the runtime CREATE statement in SupplyChainTaskRepository. Requires the target database
-- default charset utf8mb4 (see DATABASE_RELEASE section 2); no engine clause is added so the
-- statement stays identical to what the application issues.
CREATE TABLE IF NOT EXISTS chainpilot_analysis_task (
    task_id             VARCHAR(36) PRIMARY KEY,
    question            VARCHAR(2000) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    current_step        VARCHAR(64) NOT NULL,
    validation_attempts INTEGER NOT NULL,
    error_message       VARCHAR(1000),
    updated_at          BIGINT NOT NULL,
    report_json         TEXT,
    task_type           VARCHAR(24) NOT NULL DEFAULT 'ANALYSIS',
    decision_json       TEXT,
    owner_instance_id   VARCHAR(64),
    lease_expires_at    BIGINT,
    fence_version       BIGINT NOT NULL DEFAULT 0
);

-- ── 2. Upgrade path: add the ownership columns to an existing task table ────────────────────
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
              AND table_name = 'chainpilot_analysis_task' AND column_name = 'owner_instance_id') = 0,
             'ALTER TABLE chainpilot_analysis_task ADD COLUMN owner_instance_id VARCHAR(64) NULL',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
              AND table_name = 'chainpilot_analysis_task' AND column_name = 'lease_expires_at') = 0,
             'ALTER TABLE chainpilot_analysis_task ADD COLUMN lease_expires_at BIGINT NULL',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- NOT NULL DEFAULT 0 backfills existing rows with fence 0, i.e. "never leased". Recovery ignores
-- those rows because owner_instance_id stays NULL, so an upgrade cannot fail live work.
SET @s := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
              AND table_name = 'chainpilot_analysis_task' AND column_name = 'fence_version') = 0,
             'ALTER TABLE chainpilot_analysis_task ADD COLUMN fence_version BIGINT NOT NULL DEFAULT 0',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 3. Evaluation lease lock table + the single AGENT_EVALUATION row ────────────────────────
CREATE TABLE IF NOT EXISTS chainpilot_evaluation_lock (
    lock_name          VARCHAR(64) PRIMARY KEY,
    owner_instance_id  VARCHAR(64),
    lease_expires_at   BIGINT NOT NULL,
    fence_version      BIGINT NOT NULL,
    run_id             VARCHAR(36),
    updated_at         BIGINT NOT NULL
);

SET @s := IF((SELECT COUNT(*) FROM chainpilot_evaluation_lock WHERE lock_name = 'AGENT_EVALUATION') = 0,
             'INSERT INTO chainpilot_evaluation_lock (lock_name, owner_instance_id, lease_expires_at, fence_version, run_id, updated_at) VALUES (''AGENT_EVALUATION'', NULL, 0, 0, NULL, 0)',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 4. POST-CHECK (all must hold before the application is started) ─────────────────────────
SELECT COUNT(*) AS ownership_columns FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'chainpilot_analysis_task'
  AND column_name IN ('owner_instance_id', 'lease_expires_at', 'fence_version');
SELECT COUNT(*) AS lock_table FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'chainpilot_evaluation_lock';
SELECT COUNT(*) AS lock_rows FROM chainpilot_evaluation_lock WHERE lock_name = 'AGENT_EVALUATION';
SELECT ENGINE, TABLE_COLLATION FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name IN ('chainpilot_analysis_task', 'chainpilot_evaluation_lock');
-- No task may have been rewritten by the migration: owner stays NULL and fence stays 0.
SELECT COUNT(*) AS untouched_tasks FROM chainpilot_analysis_task
  WHERE owner_instance_id IS NULL AND lease_expires_at IS NULL AND fence_version = 0;
SELECT status, COUNT(*) AS rows_after FROM chainpilot_analysis_task GROUP BY status;

-- ── 5. Rollback ────────────────────────────────────────────────────────────────────────────
-- The new application version reads these columns, so rollback means rolling the application back
-- to the previous build first. The previous build tolerates the extra columns (it never selects
-- them by name); dropping them is optional and must not be done while a new build is running:
--   ALTER TABLE chainpilot_analysis_task DROP COLUMN fence_version,
--     DROP COLUMN lease_expires_at, DROP COLUMN owner_instance_id;
--   DROP TABLE chainpilot_evaluation_lock;
