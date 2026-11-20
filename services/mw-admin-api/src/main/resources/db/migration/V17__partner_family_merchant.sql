-- Family merchant (ticket 115, FRS UC-11 / EXT-11): partners served by mw-adapter-merchant (merchant
-- systems over SOAP: validate, payment notify, refund). Additive: every existing row keeps its family.
ALTER TABLE partner.partner DROP CONSTRAINT partner_family_check;
ALTER TABLE partner.partner ADD CONSTRAINT partner_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant'));
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_family_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant'));
