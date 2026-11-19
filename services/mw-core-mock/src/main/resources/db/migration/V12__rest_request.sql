-- The core wallet REST API (ADR D31, ticket 93): idempotent by txnId and operation (FRS 7.3). One row
-- per operation key (the commandId <txnId>:rest:<op> of command_log) with the hash of the request body
-- it was first called with: a repeat with the same body is answered from command_log, a different
-- body is 409 MW-IDEM-001.
CREATE TABLE rest_request (
  operation_key text        PRIMARY KEY,
  request_hash  text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);
