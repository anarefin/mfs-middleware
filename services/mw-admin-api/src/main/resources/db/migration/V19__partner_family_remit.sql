-- Family remit (ticket 119, FRS UC-16 / EXT-15 / FL-10): partners served by mw-adapter-remit (inbound
-- international remittance, UCB IMT: push credit and its status). Additive: every existing row keeps
-- its family.
ALTER TABLE partner.partner DROP CONSTRAINT partner_family_check;
ALTER TABLE partner.partner ADD CONSTRAINT partner_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card', 'remit'));
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_family_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card', 'remit'));
