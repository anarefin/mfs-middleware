package mfs.mw.orchestrator.util;

import java.util.List;
import java.util.Optional;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;

/**
 * What the keyed search hashes are taken over (A2/A3): the initiator {@code <party type>:<id>} and
 * the payee of a collection, {@code BILLER:<billerCode>} for a bill payment or {@code
 * MERCHANT:<wallet>} for a payment to a merchant wallet. An org is the payee of the billers and
 * merchant wallets it is configured to collect for ({@code mw.orchestrator.search.org-payees}).
 */
public final class SearchKeys {

  public static final String ORG = "ORG";
  public static final String BILLER = "BILLER";
  public static final String MERCHANT = "MERCHANT";

  /** Initiator types whose id is an MSISDN: an MSISDN search matches these and nothing else. */
  public static final List<String> MSISDN_TYPES = List.of("MSISDN", "WALLET");

  /** Payee types an org can collect for. */
  public static final List<String> PAYEE_TYPES = List.of(BILLER, MERCHANT);

  private SearchKeys() {}

  /** A typed id; never logged (the id may be an MSISDN). */
  public record Key(String type, @Sensitive(SensitiveType.PARTY_ID) String id) {

    /** Parses {@code TYPE:id}, as written in the org payee config. */
    public static Key parse(String typed) {
      int colon = typed == null ? -1 : typed.indexOf(':');
      if (colon <= 0 || colon == typed.length() - 1) {
        throw new IllegalArgumentException("expected TYPE:id");
      }
      String type = typed.substring(0, colon).strip();
      if (!PAYEE_TYPES.contains(type)) {
        throw new IllegalArgumentException("payee type must be one of " + PAYEE_TYPES);
      }
      return new Key(type, typed.substring(colon + 1).strip());
    }

    @Override
    public String toString() {
      return "Key[type=" + type + ", id=****]";
    }
  }

  /** The initiator's typed id, or empty when the request has none. */
  public static Optional<Key> initiator(TxnRequest r) {
    Party p = r.initiator();
    return p == null || p.type() == null || p.id() == null ? Optional.empty() : Optional.of(new Key(p.type(), p.id()));
  }

  /**
   * Who collects the money: the biller of a BILL_PAY, the merchant wallet of a payment to one;
   * empty for any other transaction.
   */
  public static Optional<Key> payee(TxnRequest r) {
    if ("BILL_PAY".equals(r.txnType()) && r.partner() != null && r.partner().billerCode() != null) {
      return Optional.of(new Key(BILLER, r.partner().billerCode()));
    }
    Party credit = r.creditParty();
    if (credit != null && credit.id() != null
        && (MERCHANT.equals(credit.type()) || ("WALLET".equals(credit.type()) && MERCHANT.equals(credit.walletType())))) {
      return Optional.of(new Key(MERCHANT, credit.id()));
    }
    return Optional.empty();
  }
}
