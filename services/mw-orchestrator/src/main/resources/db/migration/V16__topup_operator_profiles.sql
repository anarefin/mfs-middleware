-- Ticket 112c: GP and Robi top-up route to the operator OCS profile, with the legacy
-- single OCS as the fallback when that profile's health is DOWN or MAINTENANCE.
-- A channel that still names the operator (gp, robi) keeps the legacy wire: routing
-- uses the operator id as the connector and the profile as the health it checks.
UPDATE routing_rule
SET primary_partner_id = 'gp-ocs', fallbacks = '{ocs}', version = version + 1
WHERE id = 'topup-gp';

UPDATE routing_rule
SET primary_partner_id = 'robi-ocs', fallbacks = '{ocs}', version = version + 1
WHERE id = 'topup-robi';
