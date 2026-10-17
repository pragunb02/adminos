-- V42: learned categoriser models (AOS-707). One serialised model per user, retrained nightly and on demand.
-- The model holds n-grams of the user's payee names and its weights: personal data, deleted with the user.
-- checksum is the SHA-256 (hex) of model, checked on every load so a damaged row is ignored, not trusted.
CREATE TABLE category_models (
  user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  algorithm           VARCHAR(40)  NOT NULL,
  format_version      INTEGER      NOT NULL CHECK (format_version >= 1),
  model               BYTEA        NOT NULL,
  checksum            CHAR(64)     NOT NULL,
  mode                VARCHAR(10)  NOT NULL CHECK (mode IN ('apply', 'suggest')),
  training_rows       INTEGER      NOT NULL CHECK (training_rows >= 0),
  corrections         INTEGER      NOT NULL CHECK (corrections >= 0),
  categories          INTEGER      NOT NULL CHECK (categories >= 0),
  held_out            INTEGER      NOT NULL CHECK (held_out >= 0),
  held_out_precision  DOUBLE PRECISION CHECK (held_out_precision IS NULL OR (held_out_precision >= 0 AND held_out_precision <= 1)),
  calibration_error   DOUBLE PRECISION CHECK (calibration_error IS NULL OR (calibration_error >= 0 AND calibration_error <= 1)),
  temperature         DOUBLE PRECISION NOT NULL CHECK (temperature > 0),
  trained_at          TIMESTAMPTZ  NOT NULL
);
