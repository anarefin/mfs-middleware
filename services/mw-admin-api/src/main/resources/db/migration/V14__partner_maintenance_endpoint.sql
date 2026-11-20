-- Partner health, maintenance and endpoint profile (tickets 97, 131b). The adapters' partner record
-- has carried maintenance and healthCheck since ticket 97; mw-admin-api kept neither, so every
-- republish (timeouts, error map, ...) dropped them. 131b adds the maintenance window (start, end,
-- bn/en message) and the endpoint profile: the route calls url={{endpoint}}, filled from endpoints
-- (MOCK, REAL) by the selected endpoint. No secrets here.
ALTER TABLE partner.partner ADD COLUMN maintenance boolean;
ALTER TABLE partner.partner ADD COLUMN health_check boolean;
ALTER TABLE partner.partner ADD COLUMN maintenance_window jsonb;
ALTER TABLE partner.endpoint ADD COLUMN endpoint_profile varchar(8) CHECK (endpoint_profile IN ('MOCK', 'REAL'));
ALTER TABLE partner.endpoint ADD COLUMN endpoints jsonb;
