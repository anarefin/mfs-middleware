-- Signed daily anchor (spec 14.3, ticket 74): one row per UTC day over the head of every monthly
-- chain written that day ({date, heads[{partition, hash, rowCount}], prevAnchorHash}), signed as an
-- ES256 JWS by the mw-crypto key AUDIT_ANCHOR_SIGN. Each anchor names the SHA-256 of the previous
-- anchor's JWS, so the anchors form a chain of their own. INSERT-only like audit.event: nobody holds
-- UPDATE or DELETE and the append-only trigger refuses both even for the owner.

CREATE TABLE anchor (
  anchor_date       date        PRIMARY KEY,           -- the UTC day the anchor covers
  payload           jsonb       NOT NULL,              -- the signed claims, for reading only
  jws               text        NOT NULL,              -- compact JWS: what is verified
  kid               text        NOT NULL,              -- <keyId>.v<n> of the signing key version
  anchor_hash       bytea       NOT NULL,              -- SHA-256 of jws (the next anchor's prevAnchorHash)
  prev_anchor_hash  bytea       NOT NULL,              -- 32 zero bytes for the first anchor
  created_at        timestamptz NOT NULL
);

CREATE TRIGGER anchor_append_only
  BEFORE UPDATE OR DELETE ON anchor
  FOR EACH ROW EXECUTE FUNCTION refuse_change();

-- The heads of one day: the last row each chain received before midnight UTC.
CREATE INDEX event_recorded_at_idx ON event (recorded_at);

REVOKE ALL ON anchor FROM PUBLIC;
GRANT SELECT, INSERT ON anchor TO audit_writer;
GRANT SELECT ON anchor TO audit_reader;
