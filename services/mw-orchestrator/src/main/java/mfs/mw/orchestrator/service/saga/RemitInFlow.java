package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/**
 * Inbound remittance from UCB IMT (ticket 119b, FRS UC-16, FL-10, 5.3 inbound template). The adapter
 * (mw-adapter-remit) accepted it and answered the partner, so the transaction starts from {@code
 * mw.partner.inbound.v1} kind REMITTANCE, never from a channel. Debit party {@code {BANK_ACCOUNT,
 * <partnerId>}} (outside the core), credit party the beneficiary's WALLET. Validate (the core checks
 * the beneficiary: exists, active, KYC level and inbound limits), then CreditWallet from {@link
 * #SETTLEMENT_WALLET}: inbound money with no partner leg, so a valid beneficiary goes straight from
 * VALIDATING to COMMITTING ({@link SagaEvent.CreditValidated}). A refused credit after acceptance ends
 * MANUAL_REVIEW (5.3 step 6: never silently dropped). Every outcome goes back to the adapter as a PAY
 * on {@link #REMIT} ({@code SagaEngine.sendRemitConfirmation}).
 */
@Component
public class RemitInFlow implements Flow {

  public static final String TXN_TYPE = "REMIT_IN";
  /** The core system wallet UCB IMT settles from (mw-core-mock seed system-wallets.csv). */
  public static final String SETTLEMENT_WALLET = "SETTLEMENT_UCB_IMT";
  /** The adapter's request topic: the outcome confirmation goes back as a PAY (family remit). */
  public static final PartnerCall REMIT = new PartnerCall("remit", "mw.partner.request.remit.v1");

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
}
