-- Partner routes only (ADR D26, ticket 84): the old partner shape (route template + base URL +
-- operations, action UPSERT) is gone from the adapters and from this service, so its columns and
-- table go too. Every partner is a route partner; its route fields live in partner.partner_route.
-- A seed of the old shape (version 0, never published) is dropped so the seed job stores it again
-- in the route shape on this start.
DELETE FROM partner.partner p
 WHERE p.version = 0 AND NOT EXISTS (SELECT 1 FROM partner.partner_route r WHERE r.partner_id = p.id);
DROP TABLE partner.mapping;
ALTER TABLE partner.partner DROP COLUMN template;
ALTER TABLE partner.endpoint DROP COLUMN base_url;

-- A seed (version 0, never published) keeps its route here too, as route_status SEED: it is what
-- the adapters run from their own dev seed until a first version is approved.
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_version_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_version_check CHECK (version >= 0);
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_route_status_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_route_status_check
  CHECK (route_status IN ('SEED', 'PENDING_DRY_RUN', 'LIVE', 'REJECTED'));

-- The full value published for the version (family included): a REJECTED current version is
-- answered by republishing the newest LIVE version's value as the next version (the restart gap of
-- ticket 83: an adapter replays only the latest record per partner). Null for rows written before.
ALTER TABLE partner.partner_route ADD COLUMN published_value jsonb;
