package mfs.mw.orchestrator.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;

/**
 * What the keyed search hashes are taken over (A2/A3): the initiator {@code <party type>:<id>} and
 * the payee of a collection, {@code BILLER:<billerCode>} for a bill payment or {@code
 * MERCHANT:<wallet>} for a payment to a merchant wallet. An org is the payee of the billers and
 * merchant wallets it is configured to collect for ({@code mw.orchestrator.search.org-payees}).
 * The wallet parties ({@link #walletParties}) are keyed {@code WALLET:<MSISDN>} for the wallet
 * history (ADR D25).
 */
public final class SearchKeys {

  public static final String ORG = "ORG";
  public static final String BILLER = "BILLER";
  public static final String MERCHANT = "MERCHANT";
  public static final String WALLET = "WALLET";

  /** Money left the wallet (it is the debit party). */
  public static final String OUT = "OUT";
  /** Money reached the wallet (it is the credit party). */
  public static final String IN = "IN";

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

  /** One wallet a transaction moves money from ({@link #OUT}) or to ({@link #IN}). */
  public record WalletParty(Key key, String direction) {}

  /**
   * The wallets of {@code r} for the wallet history (ADR D25): the debit party as {@link #OUT}
   * and the credit party as {@link #IN}, each when it is a wallet (party type {@code WALLET} of
   * any wallet type, or {@code MERCHANT}), keyed {@code WALLET:<id>}. An org, a top-up MSISDN, a
   * bank account or a biller account is never a wallet here. A wallet on both sides counts once,
   * as {@link #OUT}.
   */
  public static List<WalletParty> walletParties(TxnRequest r) {
    List<WalletParty> parties = new ArrayList<>(2);
    Party debit = r.debitParty();
    Party credit = r.creditParty();
    boolean debitWallet = isWallet(debit);
    if (debitWallet) {
      parties.add(new WalletParty(new Key(WALLET, debit.id()), OUT));
    }
    if (isWallet(credit)
        && !(debitWallet && SearchHash.canonical(debit.id()).equals(SearchHash.canonical(credit.id())))) {
      parties.add(new WalletParty(new Key(WALLET, credit.id()), IN));
    }
    return List.copyOf(parties);
  }

  private static boolean isWallet(Party p) {
    return p != null && p.id() != null && !p.id().isBlank() && (WALLET.equals(p.type()) || MERCHANT.equals(p.type()));
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
