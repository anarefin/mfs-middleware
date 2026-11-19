package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * WAL-06 {@code POST /core/v1/holds} and WAL-09 {@code POST /core/v1/transfers} (contract {@code
 * TransferRequest}). The customer's authorisation travels in the {@code X-Txn-Assertion} header with
 * {@code idempotencyKey} in the body (part of the assertion's txnHash); a bulk row needs neither.
 */
public record WalletTransferRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Party debitParty,
    @NotNull @Valid Party creditParty,
    @NotNull @Valid Amount amount,
    @Size(min = 1, max = 64) String idempotencyKey) {

  /** The TxnId contract: a lower-case UUIDv7. */
  public static final String TXN_ID = "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  /** A transaction type name (P2P, CASH_OUT, BULK_DISBURSE, ...). */
  public static final String TXN_TYPE = "^[A-Z][A-Z0-9_]{0,31}$";

  /** Whether the customer must authorise it (every type but BULK_DISBURSE, as on the command). */
  public boolean authorisationRequired() {
    return !TransferPayload.BULK_DISBURSE.equals(txnType);
  }
}
