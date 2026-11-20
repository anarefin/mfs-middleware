-- Family interop (ticket 121, FRS EXT-13 / UC-19 / FL-12): partners served by mw-adapter-interop (the
-- NPSB switch over ISO 20022; also the off-us BanglaQR leg of ticket 122). Additive: every existing row
-- keeps its family.
ALTER TABLE partner.partner DROP CONSTRAINT partner_family_check;
ALTER TABLE partner.partner ADD CONSTRAINT partner_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card', 'remit', 'atm', 'interop'));
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_family_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card', 'remit', 'atm', 'interop'));
