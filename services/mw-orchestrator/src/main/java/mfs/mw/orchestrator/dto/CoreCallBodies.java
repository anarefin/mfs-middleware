package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The request bodies of the core wallet API (contract {@code api/mw-core-mock/core-wallet.v1.openapi.yaml},
 * ADR D31, ticket 95a). The customer's assertion never goes in a body: it travels in {@code
 * X-Txn-Assertion}.
 */
public final class CoreCallBodies {

  private CoreCallBodies() {}

  /** {@code TransferRequest}: validate, hold, transfer. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Transfer(
      String txnId, String txnType, Party debitParty, Party creditParty, Amount amount, String idempotencyKey) {}

  /** {@code CreditRequest}: inbound money; {@code round} an operator's forced credit (absent: the saga's). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Credit(String txnId, String txnType, Party creditParty, Amount amount, Integer round) {}

  /** {@code ReversalRequest}: no amount, so the whole original (fee included) is reversed. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Reversal(String txnId, String originalTxnId, String reason) {}

  /** {@code HoldActionRequest} of a commit or release; {@code round} an operator's decision (absent: the saga's). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HoldAction(String reason, Integer round) {}

  /** {@code ChargesRequest} (WAL-04, the quote of ticket 105); {@code customerType} the payer's wallet type. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Charges(String txnType, Amount amount, String channel, String customerType) {}

  /** {@code LimitsRequest} (WAL-05, the quote of ticket 105). */
  public record Limits(String walletId, String txnType, Amount amount) {}
}
