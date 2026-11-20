-- Ticket 118b (FRS UC-13, EXT-14): ADD_MONEY_CARD goes to the CyberSource-style acquirer (mw-adapter-card,
-- partner record cybersource). Later changes come as ROUTING_RULE records from mw-admin-api.
INSERT INTO routing_rule (id, txn_type, primary_partner_id, priority) VALUES
  ('add-money-card', 'ADD_MONEY_CARD', 'cybersource', 0);
