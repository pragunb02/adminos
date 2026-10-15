-- 002: Identity domain — users, sessions, devices

CREATE TABLE users (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email             VARCHAR(255) NOT NULL,
  name              VARCHAR(255),
  avatar_url        VARCHAR(500),
  google_id         VARCHAR(255) NOT NULL,
  phone             VARCHAR(20),
  country           VARCHAR(10) NOT NULL DEFAULT 'IN',
  timezone          VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',
  onboarding_status onboarding_status NOT NULL DEFAULT 'started',
  role              user_role NOT NULL DEFAULT 'owner',
  is_active         BOOLEAN NOT NULL DEFAULT true,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ
);

ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT uq_users_google_id UNIQUE (google_id);
CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_google_id ON users (google_id);
CREATE INDEX idx_users_onboarding ON users (onboarding_status) WHERE deleted_at IS NULL;

CREATE TABLE devices (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_type       device_type NOT NULL,
  device_name       VARCHAR(255),
  fcm_token         TEXT,
  app_version       VARCHAR(20),
  os_version        VARCHAR(20),
  sms_permission    BOOLEAN NOT NULL DEFAULT false,
  last_seen_at      TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_user_id ON devices (user_id);
CREATE INDEX idx_devices_fcm ON devices (fcm_token) WHERE fcm_token IS NOT NULL;

CREATE TABLE sessions (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash        VARCHAR(255) NOT NULL,
  device_id         UUID REFERENCES devices(id) ON DELETE SET NULL,
  ip_address        INET,
  user_agent        TEXT,
  expires_at        TIMESTAMPTZ NOT NULL,
  last_active_at    TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at        TIMESTAMPTZ
);

CREATE INDEX idx_sessions_user_id ON sessions (user_id);
CREATE INDEX idx_sessions_token_hash ON sessions (token_hash);
CREATE INDEX idx_sessions_active ON sessions (user_id) WHERE revoked_at IS NULL;
