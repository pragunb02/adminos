-- V16: An event may carry a category the user chose at entry time, e.g. cash quick-add (AOS-204).
ALTER TABLE ingest_events ADD COLUMN category VARCHAR(30);
