package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.app.gateway.client.OrchestratorQuoteClient;
import mfs.mw.app.gateway.config.PreviewProperties;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.repository.PreviewStore;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.PreviewService;
import mfs.mw.app.gateway.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The two-step API surface (ticket 105): body schemas, the group in the path, the Idempotency-Key, and
 * a confirm bound to its preview (the real {@link PreviewService}, the orchestrator's quote and the
 * submit stubbed, previews kept in memory): no previewId 400, a changed amount PREVIEW_MISMATCH, an
 * expired preview refused, nothing submitted.
 */
@WebMvcTest(PreviewController.class)
@Import({SecurityConfig.class, AppUserResolver.class, PreviewService.class, PreviewConfirmControllerTest.Previews.class})
class PreviewConfirmControllerTest {

  static final String MSISDN = "01711000001";
  static final String PREVIEW = """
      {"creditParty":{"id":"01711000002"},"amount":{"valueMinor":%d,"currency":"BDT"}}
      """;
  static final String CONFIRM = """
      {"previewId":"%s","creditParty":{"id":"01711000002"},"amount":{"valueMinor":%d,"currency":"BDT"},
       "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"}}
      """;

  /** Previews in memory instead of Redis; the fixed key and clock of the test. */
  @TestConfiguration
  static class Previews {
    static final Map<String, PreviewStore.Stored> KEPT = new ConcurrentHashMap<>();

    @Bean
    PreviewStore previewStore() {
      return new PreviewStore(null) {
        @Override
        public void save(String id, Stored preview, Duration ttl) {
          KEPT.put(id, preview);
        }

        @Override
        public Optional<Stored> find(String id) {
          return Optional.ofNullable(KEPT.get(id));
        }
      };
    }

    @Bean
    PreviewProperties previewProperties() {
      return new PreviewProperties(Duration.ofMinutes(5), "test-only-preview-signing-key-0123456789");
    }

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);
    }
  }

  private final MockMvcTester mvc;

  @MockitoBean OrchestratorQuoteClient quotes;
  @MockitoBean TransactionService transactions;

  @Autowired
  PreviewConfirmControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @BeforeEach
  void quote() {
    Previews.KEPT.clear();
    when(quotes.quote(any())).thenReturn(new Quote("P2P", new Quote.Money(100_000, "BDT"), new Quote.Money(1_850, "BDT"),
        new Quote.Money(241, "BDT"), new Quote.Money(0, "BDT"), new Quote.Money(101_850, "BDT"),
        new Quote.Limits(true, null, new Quote.Remaining(null, new Quote.Money(500_000, "BDT"), null, null, null)),
        new Party("WALLET", "0171****002", "R.", "CUSTOMER")));
    when(transactions.submit(any(), any(), any(), any())).thenReturn(SubmitResult.done(TransactionView.processing("t-1")));
  }

  static RequestPostProcessor customer() {
    return jwt().jwt(j -> j.claim("msisdn", MSISDN).claim("role", "customer").claim("amr", List.of("pin", "device")));
  }

  MvcTestResult post(String path, String key, String body) {
    var req = mvc.post().uri(path).with(customer()).contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      req = req.header("Idempotency-Key", key);
    }
    return req.exchange();
  }

  String previewId() throws Exception {
    MvcTestResult r = post("/app/v1/send-money/preview", null, PREVIEW.formatted(100_000));
    assertThat(r).hasStatus(200);
    return JsonPath.read(r.getResponse().getContentAsString(), "$.previewId");
  }

  @Test
  void aPreviewAnswersTheChargesAndAPreviewId() {
    MvcTestResult r = post("/app/v1/send-money/preview", null, PREVIEW.formatted(100_000));

    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.fee.valueMinor").isEqualTo(1_850);
    assertThat(r).bodyJson().extractingPath("$.vat.valueMinor").isEqualTo(241);
    assertThat(r).bodyJson().extractingPath("$.total.valueMinor").isEqualTo(101_850);
    assertThat(r).bodyJson().extractingPath("$.counterparty.name").isEqualTo("R.");
    assertThat(r).bodyJson().extractingPath("$.limits.remaining.daily.valueMinor").isEqualTo(500_000);
    assertThat(r).bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-11-20T06:05:00Z");
  }

  @Test
  void aPinInThePreviewOrAnUnknownGroupIsRefused() {
    String withPin = "{\"creditParty\":{\"id\":\"01711000002\"},\"amount\":{\"valueMinor\":1,\"currency\":\"BDT\"},"
        + "\"encryptedPin\":{\"keyId\":\"k\",\"ciphertext\":\"c\"}}";
    assertThat(post("/app/v1/send-money/preview", null, withPin)).hasStatus(400);
    assertThat(post("/app/v1/lottery/preview", null, PREVIEW.formatted(1))).hasStatus(404);
  }

  @Test
  void aConfirmWithoutPreviewIdIs400() {
    String noId = "{\"creditParty\":{\"id\":\"01711000002\"},\"amount\":{\"valueMinor\":100000,\"currency\":\"BDT\"},"
        + "\"encryptedPin\":{\"keyId\":\"core-pin-1\",\"ciphertext\":\"Y2lwaGVy\"}}";

    MvcTestResult r = post("/app/v1/send-money/confirm", "key-1", noId);

    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(transactions, never()).submit(any(), any(), any(), any());
  }

  @Test
  void aConfirmWithoutIdempotencyKeyIs400() throws Exception {
    MvcTestResult r = post("/app/v1/send-money/confirm", null, CONFIRM.formatted(previewId(), 100_000));

    assertThat(r).hasStatus(400);
    verify(transactions, never()).submit(any(), any(), any(), any());
  }

  @Test
  void aConfirmOfThePreviewIsSubmitted() throws Exception {
    MvcTestResult r = post("/app/v1/send-money/confirm", "key-1", CONFIRM.formatted(previewId(), 100_000));

    assertThat(r).hasStatus(200);
    verify(transactions).submit(any(), eq("key-1"), any(), any());
  }

  @Test
  void aChangedAmountIsPreviewMismatch() throws Exception {
    MvcTestResult r = post("/app/v1/send-money/confirm", "key-1", CONFIRM.formatted(previewId(), 100_001));

    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-026");
    assertThat(r).bodyJson().extractingPath("$.messageBn").isNotNull();
    verify(transactions, never()).submit(any(), any(), any(), any());
  }

  @Test
  void anExpiredPreviewIsRefused() throws Exception {
    String id = previewId();
    Previews.KEPT.clear(); // the Redis TTL ran out

    MvcTestResult r = post("/app/v1/send-money/confirm", "key-1", CONFIRM.formatted(id, 100_000));

    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-027");
    verify(transactions, never()).submit(any(), any(), any(), any());
  }
}
