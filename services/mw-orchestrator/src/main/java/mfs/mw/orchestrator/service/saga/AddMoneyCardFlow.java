package mfs.mw.orchestrator.service.saga;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Add money from a card (ticket 118b, FRS UC-13, EXT-14, FL-07): validate the receiving wallet, open a
 * hosted page session at the acquirer (mw-adapter-card, family {@code card}: CREATE_SESSION = INQUIRY),
 * wait for the customer and the acquirer's 3-D Secure webhook (CALLBACK on {@code mw.partner.inbound.v1}),
 * then AUTHORISE_CAPTURE (PAY with the session id and the transient token from the webhook) and, once the
 * capture succeeded, CreditWallet from the acquirer's settlement wallet. Card data never reaches the
 * middleware; the transient token is never stored on the transaction. Inbound money: nothing is held, a
 * decline or 3-D Secure failure fails with nothing moved, a capture UNKNOWN goes to STATUS_QUERY.
 */
@Component
public class AddMoneyCardFlow implements Flow {

  public static final String TXN_TYPE = "ADD_MONEY_CARD";
  public static final PartnerCall CARD = new PartnerCall("card", "mw.partner.request.card.v1");
  /** The core settlement wallet a card credit draws on (ticket 94, WAL-10). */
  public static final String SETTLEMENT_WALLET = "SETTLEMENT_CYBERSOURCE";

  @Override
  public String txnType() {
    return TXN_TYPE;
  }

  @Override
  public boolean needsHold() {
    return false;
  }

  @Override
  public boolean creditsWallet() {
    return true;
  }

  @Override
  public Optional<PartnerCall> partnerCall() {
    return Optional.of(CARD);
  }
}
