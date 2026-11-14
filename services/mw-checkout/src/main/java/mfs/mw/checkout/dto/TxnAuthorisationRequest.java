package mfs.mw.checkout.dto;

import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * mw-auth {@code POST /internal/v1/txn-authorisations} ({@code TxnAuthorisationRequest}, spec 18.7,
 * D22; ticket 72): the wallet by MSISDN, the channel, the PIN under the transport key and the
 * transaction with exactly the values of the {@code mw.txn.request.v1} then published (payer =
 * debitParty, payee = creditParty, amount = valueMinor), or the core's txnHash check fails.
 */
public record TxnAuthorisationRequest(
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String channel,
    EncryptedPin encryptedPin,
    Txn txn) {

  /** The transaction the assertion is bound to. */
  public record Txn(
      String txnType, long amount, String currency, TxnParty payer, TxnParty payee, String idempotencyKey) {}

  /** A party's type and id only. */
  public record TxnParty(String type, @Sensitive(SensitiveType.PARTY_ID) String id) {}
}
