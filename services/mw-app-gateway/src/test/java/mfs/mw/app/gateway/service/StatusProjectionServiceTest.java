package mfs.mw.app.gateway.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Map;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.junit.jupiter.api.Test;

/** The app history projection ({@code app_transaction}). */
class StatusProjectionServiceTest {

  static final Instant NOW = Instant.parse("2026-11-16T08:15:31Z");
  static final Party WALLET = Party.wallet("01711000001", "CUSTOMER");

  final AppTransactionRepository repo = mock(AppTransactionRepository.class);
  final StatusProjectionService projection = new StatusProjectionService(repo);

  static StatusMessage status(String txnType, String state, Map<String, String> attributes, StatusMessage.Balance balance) {
    return new StatusMessage(
        1, "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "k", "c", "CUSTOMER_APP", txnType, state, null,
        new Party("MSISDN", "01711000001", null, null), WALLET, WALLET, new Amount(0L, "BDT"), null, null, null,
        null, attributes, NOW, NOW, balance);
  }

  /** ADR D26: a balance check is never stored, so GET /app/v1/transactions/{txnId} never serves one. */
  @Test
  void aBalanceCheckIsNeverProjectedInAnyState() {
    for (String state : new String[] {"RECEIVED", "VALIDATING", "COMPLETED", "FAILED"}) {
      projection.apply(status("BALANCE", state, null,
          "COMPLETED".equals(state) ? new StatusMessage.Balance(new Amount(1_200_000L, "BDT"), NOW) : null));
    }
    projection.apply(status("BALANCE", "COMPLETED",
        Map.of("duplicate", "true", "requestTxnId", "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0e"), null));
    verifyNoInteractions(repo);
  }

  @Test
  void anyOtherTypeIsProjected() {
    projection.apply(status("P2P", "COMPLETED", null, null));
    verify(repo).upsert(eq("01711000001"), eq("CUSTOMER_APP"), any(), eq(NOW));
  }
}
