package mfs.mw.core.mock.security;

import mfs.mw.core.mock.dto.WalletTransferRequest;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;

/**
 * The {@code X-Txn-Assertion} header of the core wallet API (D22, D31, ticket 93): a hold or transfer
 * the customer must authorise (every type but BULK_DISBURSE) needs mw-auth's assertion and the
 * request's {@code idempotencyKey} it was signed over, else {@code 401 MW-AUTH-046} before anything
 * runs. Whether the assertion itself holds is {@code service/AssertionCheck}'s.
 */
public final class TxnAssertionHeader {

  public static final String NAME = "X-Txn-Assertion";

  private TxnAssertionHeader() {}

  /** The header value when the request needs it (blank = absent); throws when it is missing. */
  public static String require(WalletTransferRequest req, String header) {
    String assertion = header == null || header.isBlank() ? null : header;
    boolean keyMissing = req.idempotencyKey() == null || req.idempotencyKey().isBlank();
    if (!req.authorisationRequired() && assertion == null) {
      return null;
    }
    // required, or sent anyway (a bulk row): it is only checkable with the key it was signed over
    if (assertion == null || keyMissing) {
      throw new MwException(ErrorCodes.AUTH_ASSERTION, "Transaction authorisation missing").withTxnId(req.txnId());
    }
    return assertion;
  }
}
