package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * WAL-06 {@code POST /core/v1/holds} and WAL-09 {@code POST /core/v1/transfers} (contract {@code
 * TransferRequest}). The customer's authorisation travels in the {@code X-Txn-Assertion} header with
 * {@code idempotencyKey} in the body (part of the assertion's txnHash); a bulk row needs neither. A
 * hold's optional {@code settlementWallet} names the partner settlement wallet its commit credits
 * (ticket 94; absent = SETTLEMENT); a transfer ignores it.
 */
public record WalletTransferRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Party debitParty,
    @NotNull @Valid Party creditParty,
    @NotNull @Valid Amount amount,
    @Size(min = 1, max = 64) String idempotencyKey,
    @Size(min = 1, max = 64) @Pattern(regexp = WalletTransferRequest.SETTLEMENT) String settlementWallet) {

  /** A settlement wallet id (ticket 94): SETTLEMENT or SETTLEMENT_<partner>. */
  public static final String SETTLEMENT = "^SETTLEMENT(_[A-Z0-9_]+)?$";

  /** The TxnId contract: a lower-case UUIDv7. */
  public static final String TXN_ID = "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  /** A transaction type name (P2P, CASH_OUT, BULK_DISBURSE, ...). */
  public static final String TXN_TYPE = "^[A-Z][A-Z0-9_]{0,31}$";

  /** Whether the customer must authorise it (every type but BULK_DISBURSE, as on the command). */
  public boolean authorisationRequired() {
    return !TransferPayload.BULK_DISBURSE.equals(txnType);
  }
}
