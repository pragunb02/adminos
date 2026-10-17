-- AOS-709 (D-75): merchant names the user confirmed are one merchant, and pairs they said are not.
-- Keys are AdminOS's merchant keys (the stored merchant name, trimmed and lower-cased).
--
-- merchant_aliases: payments under alias_key belong to canonical_name. Aliases never chain: merging into
-- a merchant that is itself merged later moves its aliases along (enforced by the application).
CREATE TABLE merchant_aliases (
  user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  alias_key       VARCHAR(255) NOT NULL,
  canonical_key   VARCHAR(255) NOT NULL,
  canonical_name  VARCHAR(255) NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, alias_key),
  CHECK (alias_key <> canonical_key)
);
CREATE INDEX idx_merchant_aliases_canonical ON merchant_aliases (user_id, canonical_key);

-- merchant_merge_rejections: "these two are different", never proposed together again (first_key < second_key).
CREATE TABLE merchant_merge_rejections (
  user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  first_key   VARCHAR(255) NOT NULL,
  second_key  VARCHAR(255) NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, first_key, second_key),
  CHECK (first_key < second_key)
);
