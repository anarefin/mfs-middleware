-- Partner routes as config (ADR D28, ticket 83), schema `partner` (own). One row per approved
-- UPSERT_ROUTE version (and per later ENABLE / DISABLE / SET_TIMEOUTS of a route partner, which
-- republish the route): the Camel YAML DSL route, its named JSONata mappings, the hosts it may call,
-- the dry-run it must pass, the family whose adapter applies it, and what that adapter reported on
-- mw.partner.route.status.v1 (PENDING_DRY_RUN until a report arrives, then LIVE or REJECTED with
-- its problems and, when LIVE, the Kamelets it uses). The partner's current version is
-- partner.partner.version; older rows stay as the history (and the last LIVE one).
CREATE TABLE partner.partner_route (
  partner_id    varchar(64) NOT NULL REFERENCES partner.partner (id) ON DELETE CASCADE,
  version       bigint      NOT NULL CHECK (version >= 1),
  family        varchar(16) NOT NULL CHECK (family IN ('telco', 'billpay', 'bank')),
  route_yaml    text        NOT NULL,
  mappings      jsonb       NOT NULL,
  allowed_hosts jsonb       NOT NULL,
  dry_run       jsonb       NOT NULL,
  route_status  varchar(16) NOT NULL CHECK (route_status IN ('PENDING_DRY_RUN', 'LIVE', 'REJECTED')),
  problems      jsonb,
  kamelets      jsonb,
  created_at    timestamptz NOT NULL,
  status_at     timestamptz,
  PRIMARY KEY (partner_id, version)
);

-- A route partner has no base URL (its route holds the call URLs); the endpoint row still carries
-- its callback auth and static headers.
ALTER TABLE partner.endpoint ALTER COLUMN base_url DROP NOT NULL;
