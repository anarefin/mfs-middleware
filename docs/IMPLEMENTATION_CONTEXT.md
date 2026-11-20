## 114
INTERFACE: catalogue BILL_PAY rules are orchestrator Flyway V19 (`V19__bill_aggregator_routing.sql`); Newman folder stays `tests/api/src/51-bill-aggregators`
DECISION: V19 because upay-main already took V18 for bank routing; a sent or unknown PAY is not a preSendRefusal, so it is not retried on the fallback
CONSTRAINT: do not reuse V18; do not renumber folder 51 (50 is checkout refund; 52-54 are reserved); PAY already sent is not a preSendRefusal
DEFERRED: Newman folder 51 not executed; T5/T11/T12 stay n/a (27s aggregator read timeout; tickets 125 and 127); agg4 stays JSON
