package mfs.mw.orchestrator.service.saga;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Transfer to bank, W2B (ticket 117c, FRS UC-15, FL-09): validate, hold the payer's funds, ask the
 * bank (mw-adapter-bank, family {@code bank}, partner {@code bank-<code>}) to credit the account in
 * {@code creditParty} ({@code BANK_ACCOUNT}, the customer's linked account as mw-app-gateway decrypted
 * it), then commit the hold on the bank's confirmation (reply or callback) or release it on a definite
 * refusal; a lost answer goes to UNKNOWN and the bank's STATUS_QUERY. Same steps as TOPUP.
 */
@Component
public class W2bFlow implements Flow {

  public static final String TXN_TYPE = "W2B";

  @Override
  public String txnType() {
    return TXN_TYPE;
  }

  @Override
  public boolean needsHold() {
    return true;
  }

  @Override
  public Optional<PartnerCall> partnerCall() {
    return Optional.of(AddMoneyBankFlow.BANK);
  }
}
