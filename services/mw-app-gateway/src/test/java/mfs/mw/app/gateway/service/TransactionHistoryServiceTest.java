package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import mfs.mw.app.gateway.client.OrchestratorQueryClient;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.QueryTxn;
import mfs.mw.app.gateway.dto.QueryTxnPage;
import mfs.mw.app.gateway.dto.TransactionHistory;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.exception.OrchestratorUnavailableException;
import mfs.mw.app.gateway.mapper.HistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** The wallet history (ADR D25): the token's wallet only, paging by one extra item, MW-SYS-013. */
class TransactionHistoryServiceTest {

  static final AppUser CUSTOMER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
  static final Instant AT = Instant.parse("2026-11-16T06:00:00Z");

  private final OrchestratorQueryClient client = mock(OrchestratorQueryClient.class);
  private final TransactionHistoryService service = new TransactionHistoryService(client, new HistoryMapper());

  static QueryTxn txn(int i) {
    return new QueryTxn("019a4f6e-1c2d-7a3b-8c4d-00000000000" + i, "P2P", i % 2 == 0 ? "OUT" : "IN", "COMPLETED",
        AT, AT, new Amount(100L, "BDT"), null, Party.wallet("0171****001", "CUSTOMER"),
        Party.wallet("0171****002", "CUSTOMER"), null, null);
  }

  static QueryTxnPage page(int n) {
    return new QueryTxnPage(IntStream.range(0, n).mapToObj(TransactionHistoryServiceTest::txn).toList(), n, 0);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> askedFor() {
    ArgumentCaptor<Map<String, Object>> filters = ArgumentCaptor.forClass(Map.class);
    verify(client).walletHistory(eq("01711000001"), filters.capture());
    return filters.getValue();
  }

  @Test
  void asksForTheTokensWalletWithOneMoreItemAndTheFilters() {
    when(client.walletHistory(any(), anyMap())).thenReturn(page(3));

    TransactionHistory h = service.history(CUSTOMER, 2, 4, "P2P", "IN", AT, AT.plusSeconds(60));

    assertThat(h.items()).hasSize(2);
    assertThat(h.hasMore()).isTrue();
    assertThat(h.limit()).isEqualTo(2);
    assertThat(h.offset()).isEqualTo(4);
    Map<String, Object> f = askedFor();
    assertThat(f).containsEntry("limit", 3).containsEntry("offset", 4).containsEntry("txnType", "P2P")
        .containsEntry("direction", "IN").containsEntry("from", AT).containsEntry("to", AT.plusSeconds(60))
        .doesNotContainKey("walletId");
  }

  @Test
  void theLastPageHasNoMore() {
    when(client.walletHistory(any(), anyMap())).thenReturn(page(2));

    TransactionHistory h = service.history(CUSTOMER, 20, 0, null, null, null, null);

    assertThat(h.items()).extracting(i -> i.direction()).containsExactly("OUT", "IN");
    assertThat(h.hasMore()).isFalse();
    assertThat(askedFor()).containsEntry("limit", 21).containsEntry("txnType", null);
  }

  @Test
  void anEmptyHistoryIsAnEmptyPage() {
    when(client.walletHistory(any(), anyMap())).thenReturn(new QueryTxnPage(List.of(), 21, 0));
    TransactionHistory h = service.history(CUSTOMER, 20, 0, null, null, null, null);
    assertThat(h.items()).isEmpty();
    assertThat(h.hasMore()).isFalse();
  }

  @Test
  void fromNotBeforeToIsMwValAndNothingIsAsked() {
    assertThatThrownBy(() -> service.history(CUSTOMER, 20, 0, null, null, AT, AT))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
    verify(client, never()).walletHistory(any(), anyMap());
  }

  @Test
  void anUnavailableOrchestratorOrAnOpenBreakerIsMwSys013Retryable() {
    for (RuntimeException failure : List.of(
        new OrchestratorUnavailableException("down", null),
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("orchestrator")))) {
      doThrow(failure).when(client).walletHistory(any(), anyMap());
      assertThatThrownBy(() -> service.history(CUSTOMER, 20, 0, null, null, null, null))
          .isInstanceOfSatisfying(MwException.class, e -> {
            assertThat(e.code()).isEqualTo("MW-SYS-013");
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.retryable()).isTrue();
          });
    }
  }
}
