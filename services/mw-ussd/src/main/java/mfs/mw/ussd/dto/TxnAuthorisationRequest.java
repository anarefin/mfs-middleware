package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * Body of mw-auth {@code POST /internal/v1/txn-authorisations} (contract {@code
 * api/mw-auth/auth-internal.v1.openapi.yaml}, ticket 72): the dialling subscriber's MSISDN, the
 * channel, the PIN under the transport key and the transaction with exactly the values of the
 * {@code mw.txn.request.v1} published next (the core recomputes the assertion's txnHash from them).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxnAuthorisationRequest(
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String channel,
    @Sensitive(SensitiveType.SECRET) EncryptedPin encryptedPin,
    Txn txn) {

  /** The transaction: amount in minor units, payer = debitParty, payee = creditParty. */
  public record Txn(
      String txnType, long amount, String currency, TxnParty payer, TxnParty payee, String idempotencyKey) {}

  /** A party's type and id only. */
  public record TxnParty(String type, @Sensitive(SensitiveType.PARTY_ID) String id) {}
}
