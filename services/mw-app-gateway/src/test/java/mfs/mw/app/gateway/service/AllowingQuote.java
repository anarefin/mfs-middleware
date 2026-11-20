package mfs.mw.app.gateway.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import mfs.mw.app.gateway.client.OrchestratorQuoteClient;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.QuoteRequest;

/**
 * A quote the tests of {@code POST /app/v1/transactions} need now that the endpoint runs preview then
 * confirm (ticket 105). The limit allows the amount; the charges are zero so the assertions of those
 * tests stay on the saga, not on a tariff.
 */
public final class AllowingQuote {

  private AllowingQuote() {}

  public static void allow(OrchestratorQuoteClient quotes) {
    when(quotes.quote(any())).thenAnswer(inv -> {
      QuoteRequest asked = inv.getArgument(0);
      long amount = asked.amount().valueMinor();
      String currency = asked.amount().currency();
      Quote.Money money = new Quote.Money(amount, currency);
      Quote.Money zero = new Quote.Money(0, currency);
      return new Quote(asked.txnType(), money, zero, zero, zero, money,
          new Quote.Limits(true, null, new Quote.Remaining(null, null, null, null, null)), null);
    });
  }
}
