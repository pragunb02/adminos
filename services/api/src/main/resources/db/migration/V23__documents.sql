-- V23: Documents vault (AOS-507, D-60). The files themselves are encrypted in object storage (Cloudflare R2);
-- this table holds what's needed to find, show and decrypt them. wrapped_key is the file's own key,
-- encrypted with the app key; the storage key contains no name or user detail.
CREATE TABLE documents (
  id                    UUID PRIMARY KEY,
  user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title                 VARCHAR(120) NOT NULL,
  category              VARCHAR(16) NOT NULL,
  file_type             VARCHAR(8) NOT NULL,
  size_bytes            BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
  sha256                CHAR(64) NOT NULL,
  wrapped_key           TEXT NOT NULL,
  storage_key           VARCHAR(200) NOT NULL UNIQUE,
  original_name         VARCHAR(100),
  expires_on            DATE,
  notes                 TEXT,
  expiry_reminders_sent TEXT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_documents_user ON documents (user_id, created_at DESC);
CREATE INDEX idx_documents_expiry ON documents (expires_on) WHERE expires_on IS NOT NULL;
