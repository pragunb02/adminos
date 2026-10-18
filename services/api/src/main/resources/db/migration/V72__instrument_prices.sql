-- V72: end-of-day prices (AOS-810, D-79). Public data, kept for history and valuations. One row per instrument,
-- date and source; a re-run of a day's fetch upserts. A price is never zero (a missing price is a missing row).
CREATE TABLE instrument_prices (
  instrument_id UUID           NOT NULL REFERENCES instruments(id) ON DELETE CASCADE,
  price_date    DATE           NOT NULL,
  source        VARCHAR(12)    NOT NULL CHECK (source IN ('amfi', 'statement')),
  price         NUMERIC(30,10) NOT NULL CHECK (price > 0),
  fetched_at    TIMESTAMPTZ    NOT NULL,
  PRIMARY KEY (instrument_id, price_date, source)
);
CREATE INDEX idx_instrument_prices_latest ON instrument_prices (instrument_id, price_date DESC);
