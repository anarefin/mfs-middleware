package mfs.mw.orchestrator.service.saga;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Add money from a linked bank account, C2W (spec 5.2, WP16, ticket 23): validate the receiving
 * wallet and the customer's PIN, ask the bank (mw-adapter-bank, family {@code bank}) to debit the
 * account in {@code debitParty} ({@code BANK_ACCOUNT}), then credit the wallet in {@code
 * creditParty} with CreditWallet once the bank confirmed (usually by callback). Money is inbound,
 * so nothing is held: a refusal by the bank fails the transaction with nothing to release, and a
 * lost answer goes to UNKNOWN and the bank's status inquiry.
 */
@Component
public class AddMoneyBankFlow implements Flow {

  static final PartnerCall BANK = new PartnerCall("bank", "mw.partner.request.bank.v1");

  @Override
  public String txnType() {
    return "ADD_MONEY_BANK";
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
    return Optional.of(BANK);
  }
}
