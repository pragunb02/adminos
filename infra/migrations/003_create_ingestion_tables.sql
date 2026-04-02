-- 003: Ingestion domain — user_connections, sync_sessions, raw_jobs, ingestion_fingerprints

CREATE TABLE user_connections (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_type       source_type NOT NULL,
  status            connection_status NOT NULL DEFAULT 'pending',
  access_token      TEXT,
  refresh_token     TEXT,
  token_expires_at  TIMESTAMPTZ,
  oauth_scope       TEXT[],
  gmail_address     VARCHAR(255),
  pubsub_expiry     TIMESTAMPTZ,
  history_id        VARCHAR(100),
  last_synced_at    TIMESTAMPTZ,
  last_sync_status  sync_status,
  last_error        TEXT,
  next_sync_at      TIMESTAMPTZ,
  total_synced      INTEGER NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE user_connections ADD CONSTRAINT uq_conn_source UNIQUE (user_id, source_type, gmail_address);
CREATE INDEX idx_conn_user ON user_connections (user_id);
CREATE INDEX idx_conn_status ON user_connections (status);
CREATE INDEX idx_conn_next_sync ON user_connections (next_sync_at) WHERE status = 'connected';

CREATE TABLE sync_sessions (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  connection_id     UUID NOT NULL REFERENCES user_connections(id) ON DELETE CASCADE,
  sync_type         sync_type NOT NULL,
  status            sync_session_status NOT NULL DEFAULT 'queued',
  total_items       INTEGER NOT NULL DEFAULT 0,
  processed_items   INTEGER NOT NULL DEFAULT 0,
  failed_items      INTEGER NOT NULL DEFAULT 0,
  duplicate_items   INTEGER NOT NULL DEFAULT 0,
  net_new_items     INTEGER NOT NULL DEFAULT 0,
  started_at        TIMESTAMPTZ,
  completed_at      TIMESTAMPTZ,
  error_details     JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sync_user ON sync_sessions (user_id);
CREATE INDEX idx_sync_conn ON sync_sessions (connection_id);
CREATE INDEX idx_sync_status ON sync_sessions (status) WHERE status IN ('queued', 'in_progress');

CREATE TABLE raw_jobs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sync_session_id   UUID REFERENCES sync_sessions(id) ON DELETE SET NULL,
  job_type          job_type NOT NULL,
  status            job_status NOT NULL DEFAULT 'queued',
  payload           JSONB NOT NULL,
  result            JSONB,
  retry_count       INTEGER NOT NULL DEFAULT 0,
  max_retries       INTEGER NOT NULL DEFAULT 3,
  error_message     TEXT,
  queued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at        TIMESTAMPTZ,
  completed_at      TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_status ON raw_jobs (status) WHERE status IN ('queued', 'processing', 'retrying');
CREATE INDEX idx_jobs_type ON raw_jobs (job_type, status);

CREATE TABLE ingestion_fingerprints (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  fingerprint       VARCHAR(64) NOT NULL,
  source_type       source_type NOT NULL,
  entity_type       fingerprint_entity_type NOT NULL DEFAULT 'transaction',
  entity_id         UUID NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE ingestion_fingerprints ADD CONSTRAINT uq_fp UNIQUE (user_id, fingerprint);
CREATE INDEX idx_fp_lookup ON ingestion_fingerprints (user_id, fingerprint);
