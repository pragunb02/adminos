-- V52 (AOS-910, D-90): travel bookings (from email and SMS) and the user's edits to trips.
-- Trips themselves are computed on read from transactions and bookings; only edits are stored.
-- Tentative number: the integrator renumbers at merge.

CREATE TABLE travel_bookings (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source        VARCHAR(10) NOT NULL CHECK (source IN ('gmail', 'sms')),
  source_ref    VARCHAR(255) NOT NULL,
  brand         VARCHAR(40) NOT NULL,
  mode          VARCHAR(10) NOT NULL CHECK (mode IN ('flight', 'train', 'bus')),
  -- SHA-256 of brand and PNR: the PNR itself is never kept (D-33).
  ref_hash      CHAR(64) NOT NULL,
  journey_date  DATE,
  booked_at     TIMESTAMPTZ NOT NULL,
  amount        NUMERIC(12,2) CHECK (amount > 0),
  cancelled     BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_travel_booking UNIQUE (user_id, brand, ref_hash)
);
CREATE INDEX idx_travel_bookings_user ON travel_bookings (user_id, booked_at);

CREATE TABLE trip_edits (
  trip_id        UUID PRIMARY KEY,
  user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name           VARCHAR(80),
  dismissed      BOOLEAN NOT NULL DEFAULT false,
  -- Comma-separated transaction UUIDs (D-10).
  added_ids      TEXT,
  removed_ids    TEXT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trip_edits_user ON trip_edits (user_id);
