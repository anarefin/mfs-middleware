package mfs.mw.ussd.client;

import java.util.List;
import mfs.mw.ussd.service.AccountReads;
import mfs.mw.ussd.service.AccountScreens;
import mfs.mw.ussd.service.ChargeQuote;
import mfs.mw.ussd.service.ChargeQuote.Quoted;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test double for the core reads ({@code mw.ussd.core.mode=stub}): the dev tariff for a fee, an
 * empty statement, and no remittance. It does not call the core.
 */
@Component
@ConditionalOnProperty(name = "mw.ussd.core.mode", havingValue = "stub")
public class StubCoreReads implements ChargeQuote, AccountReads {

  @Override
  public Quoted quote(String txnType, long amountMinor, String customerType) {
    long fee = SeedTariff.feeMinor(txnType, amountMinor);
    return new Quoted(fee, amountMinor + fee);
  }

  @Override
  public String statement(String walletId, String language) {
    return AccountScreens.statement(language, List.of());
  }

  @Override
  public String remitStatus(String reference, String language) {
    return AccountScreens.noRemit(language);
  }
}
