-- Ticket 117c (FRS UC-15, FL-09, EXT-12): one routing rule per bank profile of
-- deploy/partner-routes/bank/bank-profiles.csv, for W2B (transfer to bank) and ADD_MONEY_BANK from a
-- linked account. The channel names the profile bank-<code> (the linked account's bank); the rule's
-- bank is that id and its connector the same profile. A bank added to the CSV adds its two rows here
-- (or a ROUTING_RULE change in mw-admin-api).
INSERT INTO routing_rule (id, txn_type, bank_code, primary_partner_id, priority)
SELECT kind || '-bank-' || code, txn_type, 'bank-' || code, 'bank-' || code, 0
FROM (VALUES ('w2b', 'W2B'), ('add-money', 'ADD_MONEY_BANK')) AS t(kind, txn_type)
CROSS JOIN (VALUES
  ('dbbl'), ('brac'), ('city'), ('ebl'), ('ucb'), ('mtb'), ('prime'), ('pubali'), ('dhaka'), ('one'),
  ('southeast'), ('standard'), ('sonali'), ('janata'), ('agrani'), ('rupali'), ('ibbl'), ('ab'), ('premier'), ('nrbc')
) AS b(code);
