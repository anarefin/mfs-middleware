package mfs.mw.auth.service;

import java.util.List;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.TxnAuthorisationRequest;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.http.HttpStatus;

/**
 * The amount rule of {@code POST /internal/v1/txn-authorisations} (contract {@code auth-internal.v1},
 * ADR D26): the amount is at least 1 for every txnType except {@code BALANCE}, a balance inquiry,
 * which must carry 0 with the payee equal to the payer (type and id). Else 400 MW-VAL-001.
 */
final class TxnAmountRule {

  static final String BALANCE = "BALANCE";

  private TxnAmountRule() {}

  static void check(TxnAuthorisationRequest.Txn t) {
    if (BALANCE.equals(t.txnType())) {
      if (t.amount() != 0) {
        throw invalid("txn.amount", "must be 0 for BALANCE");
      }
      if (!t.payer().type().equals(t.payee().type()) || !t.payer().id().equals(t.payee().id())) {
        throw invalid("txn.payee", "must equal payer for BALANCE");
      }
    } else if (t.amount() < 1) {
      throw invalid("txn.amount", "must be at least 1");
    }
  }

  private static MwException invalid(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Invalid request")
        .withStatus(HttpStatus.BAD_REQUEST)
        .withDetails(List.of(new ErrorDetail(field, issue)));
  }
}
