package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.app.gateway.client.OrchestratorQuoteClient;
import mfs.mw.app.gateway.config.PreviewProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.ConfirmRequest;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.PreviewRequest;
import mfs.mw.app.gateway.dto.PreviewView;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.QuoteRequest;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.PreviewStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * The two-step financial API at the service seam (ticket 105, FRS CH-APP-05, PLT-026): the preview
 * shows the core's charges and the masked payee and keeps the canonical transaction hash under a
 * signed previewId; a confirm runs only for the same wallet and the same transaction.
 */
class PreviewServiceTest {

  static final AppUser USER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER", "dev-1");
  static final AppUser OTHER = new AppUser("01711000009", "CUSTOMER_APP", "CUSTOMER", "dev-9");
  static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

  /** Redis stand-in: the store's own contract (save with a TTL, find) in memory. */
  static final class MemoryStore extends PreviewStore {
    final Map<String, Stored> kept = new ConcurrentHashMap<>();

    MemoryStore() {
      super(null);
    }

    @Override
    public void save(String id, Stored preview, Duration ttl) {
      kept.put(id, preview);
    }

    @Override
    public Optional<Stored> find(String id) {
      return Optional.ofNullable(kept.get(id));
    }
  }

  final OrchestratorQuoteClient quotes = mock(OrchestratorQuoteClient.class);
  final TransactionService transactions = mock(TransactionService.class);
  final MemoryStore store = new MemoryStore();
  final PreviewService service = new PreviewService(
      quotes, transactions, store, new PreviewProperties(Duration.ofMinutes(5), "test-only-preview-signing-key-0123456789"),
      Clock.fixed(NOW, ZoneOffset.UTC));

  static Quote quote(boolean allowed, String reason) {
    return new Quote("P2P", new Quote.Money(100_000, "BDT"), new Quote.Money(1_850, "BDT"), new Quote.Money(241, "BDT"),
        new Quote.Money(925, "BDT"), new Quote.Money(101_850, "BDT"),
        new Quote.Limits(allowed, reason, new Quote.Remaining(new Quote.Money(2_500_000, "BDT"), null, null, 49, null)),
        new Party("WALLET", "0171****002", "R.", "CUSTOMER"));
  }

  static PreviewRequest p2p(String payee, long amount) {
    return new PreviewRequest(new TransactionRequest.CounterParty(null, payee), null, null, new Amount(amount, "BDT"), "en", null);
  }

  static ConfirmRequest confirm(String previewId, String payee, long amount, Map<String, String> attributes) {
    return new ConfirmRequest(previewId, new TransactionRequest.CounterParty(null, payee), null, null, new Amount(amount, "BDT"),
        new EncryptedPin("pin-1", "Y2lwaGVy"), "bn", null, attributes);
  }

  PreviewView previewed() {
    when(quotes.quote(any())).thenReturn(quote(true, null));
    return service.preview(USER, "send-money", p2p("01711000002", 100_000));
  }

  @Test
  void aPreviewShowsTheCoreChargesTheLimitsAndTheMaskedPayee() {
    PreviewView v = previewed();

    assertThat(v.txnType()).isEqualTo("P2P");
    assertThat(v.fee()).isEqualTo(new Quote.Money(1_850, "BDT"));
    assertThat(v.vat()).isEqualTo(new Quote.Money(241, "BDT"));
    assertThat(v.commission()).isEqualTo(new Quote.Money(925, "BDT"));
    assertThat(v.total()).isEqualTo(new Quote.Money(101_850, "BDT"));
    assertThat(v.counterparty()).isEqualTo(new Party("WALLET", "0171****002", "R.", "CUSTOMER"));
    assertThat(v.limits().remaining().dailyCount()).isEqualTo(49);
    assertThat(v.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(v.previewId()).isNotBlank();
    assertThat(store.kept).hasSize(1);
  }

  @Test
  void theQuoteIsAskedForTheTokensWalletWithTheCanonicalPayee() {
    when(quotes.quote(any())).thenReturn(quote(true, null));

    service.preview(USER, "send-money", p2p("+8801711000002", 100_000));

    ArgumentCaptor<QuoteRequest> asked = ArgumentCaptor.forClass(QuoteRequest.class);
    verify(quotes).quote(asked.capture());
    assertThat(asked.getValue()).isEqualTo(new QuoteRequest("01711000001", "CUSTOMER", "P2P", "CUSTOMER_APP",
        new Amount(100_000L, "BDT"), new QuoteRequest.Counterparty("WALLET", "01711000002")));
    ArgumentCaptor<TransactionRequest> checked = ArgumentCaptor.forClass(TransactionRequest.class);
    verify(transactions).check(eq(USER), checked.capture());
    assertThat(checked.getValue().txnType()).as("the group's type, the channel rules applied").isEqualTo("P2P");
  }

  @Test
  void aBrokenLimitIsTheCoreReasonInEnglishAndBanglaAndNoPreviewIsKept() {
    when(quotes.quote(any())).thenReturn(quote(false, "LIMIT_EXCEEDED_DAILY"));

    assertThatThrownBy(() -> service.preview(USER, "send-money", p2p("01711000002", 100_000)))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-CORE-LIMIT_EXCEEDED_DAILY");
          assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
          assertThat(e.getMessage()).contains("daily");
          assertThat(e.messageBn()).isNotBlank();
        });
    assertThat(store.kept).isEmpty();
  }

  @Test
  void aConfirmOfThePreviewedTransactionIsSubmittedWithTheGroupsTypeAndThePin() {
    String id = previewed().previewId();
    when(transactions.submit(any(), any(), any())).thenReturn(SubmitResult.processing(TransactionView.processing("t-1")));

    // the payee in another accepted form is the same payee
    service.confirm(USER, "send-money", "key-1", confirm(id, "8801711000002", 100_000, null));

    ArgumentCaptor<TransactionRequest> sent = ArgumentCaptor.forClass(TransactionRequest.class);
    verify(transactions).submit(eq(USER), eq("key-1"), sent.capture());
    assertThat(sent.getValue().txnType()).isEqualTo("P2P");
    assertThat(sent.getValue().encryptedPin()).isEqualTo(new EncryptedPin("pin-1", "Y2lwaGVy"));
    assertThat(sent.getValue().amount().valueMinor()).isEqualTo(100_000);
  }

  @Test
  void theHashCoversAmountPayeeTypeAndAttributes() {
    String id = previewed().previewId();

    for (ConfirmRequest changed : new ConfirmRequest[] {
        confirm(id, "01711000002", 100_001, null),
        confirm(id, "01711000003", 100_000, null),
        confirm(id, "01711000002", 100_000, Map.of("reference", "x"))}) {
      assertThatThrownBy(() -> service.confirm(USER, "send-money", "key-1", changed))
          .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-026"));
    }
    assertThatThrownBy(() -> service.confirm(USER, "cash-out", "key-1", confirm(id, "01711000002", 100_000, null)))
        .as("another group").isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-026"));
    verify(transactions, never()).submit(any(), any(), any());
  }

  @Test
  void anAlteredExpiredOrForeignPreviewIdIsRefused() {
    String id = previewed().previewId();
    String altered = id.substring(0, id.length() - 2) + (id.endsWith("AA") ? "BB" : "AA");
    String unsigned = id.substring(0, id.indexOf('.'));

    for (String bad : new String[] {altered, unsigned, "x", id + "x"}) {
      assertThatThrownBy(() -> service.confirm(USER, "send-money", "k", confirm(bad, "01711000002", 100_000, null)))
          .as(bad).isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-027"));
    }
    assertThatThrownBy(() -> service.confirm(OTHER, "send-money", "k", confirm(id, "01711000002", 100_000, null)))
        .as("another wallet").isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-027"));
    store.kept.clear();
    assertThatThrownBy(() -> service.confirm(USER, "send-money", "k", confirm(id, "01711000002", 100_000, null)))
        .as("expired: gone from the store").isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-027"));
    verify(transactions, never()).submit(any(), any(), any());
  }

  @Test
  void anUnknownGroupIsNotFound() {
    assertThatThrownBy(() -> service.preview(USER, "transactions", p2p("01711000002", 1)))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void eachGroupNamesItsType() {
    assertThat(PreviewService.GROUPS).containsExactlyInAnyOrderEntriesOf(Map.of(
        "send-money", "P2P", "cash-in", "CASH_IN", "cash-out", "CASH_OUT", "payment", "MERCHANT_PAY",
        "bill-pay", "BILL_PAY", "top-up", "TOPUP", "add-money", "ADD_MONEY_BANK"));
  }

  /** {@code POST /app/v1/transactions} runs the preview, then the confirm, of the same body (PLT-051). */
  @Test
  void theOneShotSubmitPreviewsThenConfirmsTheSameTransaction() {
    when(quotes.quote(any())).thenReturn(quote(true, null));
    when(transactions.submit(any(), any(), any())).thenReturn(SubmitResult.processing(TransactionView.processing("t-1")));
    TransactionRequest body = new TransactionRequest("P2P", new TransactionRequest.CounterParty(null, "01711000002"),
        new Amount(100_000L, "BDT"), new EncryptedPin("pin-1", "Y2lwaGVy"), "en", null);

    service.submitOneShot(USER, "key-1", body);

    verify(quotes).quote(any());
    ArgumentCaptor<TransactionRequest> sent = ArgumentCaptor.forClass(TransactionRequest.class);
    verify(transactions).submit(eq(USER), eq("key-1"), sent.capture());
    assertThat(sent.getValue().txnType()).isEqualTo("P2P");
    assertThat(sent.getValue().encryptedPin()).isEqualTo(new EncryptedPin("pin-1", "Y2lwaGVy"));
    assertThat(sent.getValue().amount().valueMinor()).isEqualTo(100_000);
    assertThat(store.kept).hasSize(1);
  }

  @Test
  void aOneShotOverTheLimitSubmitsNothing() {
    when(quotes.quote(any())).thenReturn(quote(false, "LIMIT_EXCEEDED_PER_TXN"));
    TransactionRequest body = new TransactionRequest("P2P", new TransactionRequest.CounterParty(null, "01711000002"),
        new Amount(600_000L, "BDT"), new EncryptedPin("pin-1", "Y2lwaGVy"), "en", null);

    assertThatThrownBy(() -> service.submitOneShot(USER, "key-1", body))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-CORE-LIMIT_EXCEEDED_PER_TXN"));
    verify(transactions, never()).submit(any(), any(), any());
    assertThat(store.kept).isEmpty();
  }
}
