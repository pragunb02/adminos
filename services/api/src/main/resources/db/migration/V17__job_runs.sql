-- V17: Scheduled job runs (AOS-501, D-50).
-- One row per (job, slot). The unique key plus a row lock make a slot run once across API instances;
-- attempts/lease_until let a failed or abandoned slot be retried a bounded number of times.
CREATE TABLE job_runs (
  id           UUID PRIMARY KEY,
  job_name     VARCHAR(63) NOT NULL,
  slot         TIMESTAMPTZ NOT NULL,
  status       VARCHAR(16) NOT NULL CHECK (status IN ('running', 'succeeded', 'failed')),
  attempts     INT NOT NULL CHECK (attempts >= 1),
  started_at   TIMESTAMPTZ NOT NULL,
  lease_until  TIMESTAMPTZ NOT NULL,
  finished_at  TIMESTAMPTZ,
  result       JSONB,
  error        TEXT,
  UNIQUE (job_name, slot)
);

CREATE INDEX idx_job_runs_job_slot ON job_runs (job_name, slot DESC);
