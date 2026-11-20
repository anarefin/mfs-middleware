-- Routing resources for maker-checker (ticket 98, FRS ADM-04). The approved value is what
-- mw.config.changed.v1 carries; mw-orchestrator applies it to its own tables. version is the
-- projection's monotonic version, the event's version.
CREATE TABLE routing_resource (
  resource_type text        NOT NULL,
  resource_id   text        NOT NULL,
  version       int         NOT NULL,
  value         jsonb,
  updated_at    timestamptz NOT NULL,
  PRIMARY KEY (resource_type, resource_id)
);
