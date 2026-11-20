-- Family card (ticket 118, FRS UC-13 / EXT-14 / FL-07): partners served by mw-adapter-card (card
-- acquiring, CyberSource style: hosted page session, authorise + capture, status, refund; tokens only,
-- never card data). Additive: every existing row keeps its family.
ALTER TABLE partner.partner DROP CONSTRAINT partner_family_check;
ALTER TABLE partner.partner ADD CONSTRAINT partner_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card'));
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_family_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card'));
