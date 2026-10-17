-- V42 (tentative number; the integrator renumbers): the evening digest (AOS-905).
-- Settings: on by default at 21:30 local time (the owner's choice), not skipping zero days.
ALTER TABLE coach_settings
  ADD COLUMN evening_digest       BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN digest_time          TIME    NOT NULL DEFAULT '21:30',
  ADD COLUMN digest_skip_zero_day BOOLEAN NOT NULL DEFAULT FALSE;

-- One digest per (user, kind, local day): the primary key is the claim, so restarts and instances can't repeat it.
CREATE TABLE coach_digests_sent (
  user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind    VARCHAR(20) NOT NULL CHECK (kind IN ('evening')),
  day     DATE        NOT NULL,
  sent_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, kind, day)
);
