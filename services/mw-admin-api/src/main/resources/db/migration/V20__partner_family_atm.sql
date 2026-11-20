-- Family atm (ticket 120a, FRS UC-17 / EXT-16): partners served by mw-adapter-atm (UCB ATM
-- cardless cash-out and cash-in over inbound SOAP). Additive: every existing row keeps its family.
ALTER TABLE partner.partner DROP CONSTRAINT partner_family_check;
ALTER TABLE partner.partner ADD CONSTRAINT partner_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card', 'remit', 'atm'));
ALTER TABLE partner.partner_route DROP CONSTRAINT partner_route_family_check;
ALTER TABLE partner.partner_route ADD CONSTRAINT partner_route_family_check
  CHECK (family IN ('telco', 'billpay', 'bank', 'merchant', 'card', 'remit', 'atm'));
