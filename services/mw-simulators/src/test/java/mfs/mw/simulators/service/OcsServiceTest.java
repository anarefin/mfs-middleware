package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.dto.OcsResponse;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.mapper.OcsMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OcsServiceTest {

  private final SimulatorProperties props =
      new SimulatorProperties(Duration.ofMillis(30), 100, List.of("ocs"));
  private final ScenarioStore scenarios = new ScenarioStore(props);
  private final OcsLedger ledger = new OcsLedger();
  private final List<String> callbacks = new ArrayList<>();
  private final CallbackClient client =
      new CallbackClient() {
        @Override
        public int post(String url, String json) {
          callbacks.add(url + " " + json);
          return 200;
        }

        @Override
        public int post(String url, String json, java.util.Map<String, String> headers) {
          return post(url, json);
        }
      };
  private final OcsMapper mapper = new OcsMapper();
  private final CallbackService callbackService =
      new CallbackService(
          ledger, new BillPaymentLedger(), scenarios, client,
          new mfs.mw.simulators.client.CallbackSigner(new org.springframework.mock.env.MockEnvironment(), java.time.Clock.systemUTC()),
          mapper, new mfs.mw.simulators.mapper.BillAggregatorMapper(java.time.Clock.systemUTC()),
          JsonMapper.builder().build(), Runnable::run);
  private double nextRandom = 0.99;
  private final RandomGenerator random =
      new Random() {
        @Override
        public double nextDouble() {
          return nextRandom;
        }
      };
  private final OcsService service =
      new OcsService(scenarios, ledger, mapper, callbackService, props, random);

  private OcsTopupRequest req(String id, String msisdn, String amount) {
    return new OcsTopupRequest(id, msisdn, amount);
  }

  private OcsResponse body(SimReply r) {
    return (OcsResponse) r.body();
  }

  @Test
  void success() {
    SimReply r = service.topup(req("T-1", "01712345678", "100.00"));
    assertThat(r.httpStatus()).isEqualTo(200);
    assertThat(body(r).status()).isEqualTo("SUCCESS");
    assertThat(body(r).code()).isEqualTo("0000");
  }

  @Test
  void businessFailureIs422AndInquiryShowsFailed() {
    SimReply r = service.topup(req("T-2", "01712345999", "100.00"));
    assertThat(r.httpStatus()).isEqualTo(422);
    assertThat(body(r).status()).isEqualTo("FAILED");
    assertThat(body(service.statusReply("T-2")).status()).isEqualTo("FAILED");
  }

  @Test
  void timeoutDelaysThenAnswersButTheTopupWasApplied() {
    long t0 = System.nanoTime();
    SimReply r = service.topup(req("T-3", "01712345678", "100.13"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(25));
    assertThat(r.httpStatus()).isEqualTo(200);
    assertThat(body(service.statusReply("T-3")).status()).isEqualTo("SUCCESS");
  }

  @Test
  void scenarioTimeoutDelayOverridesTheDefault() {
    scenarios.set("ocs", Scenario.DEFAULT.withTimeoutDelayMs(1L));
    long t0 = System.nanoTime();
    service.topup(req("T-3b", "01712345678", "100.13"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(25));
  }

  @Test
  void serverErrorAfterSendIs500ButTheTopupWasApplied() {
    SimReply r = service.topup(req("T-4", "01712345678", "100.77"));
    assertThat(r.httpStatus()).isEqualTo(500);
    assertThat(body(service.statusReply("T-4")).status()).isEqualTo("SUCCESS");
  }

  @Test
  void unknownCodeIs422WithAnUnmappedCode() {
    SimReply r = service.topup(req("T-5", "01712345678", "100.99"));
    assertThat(r.httpStatus()).isEqualTo(422);
    assertThat(body(r).code()).isEqualTo("9876");
  }

  @Test
  void malformedAnswers200WithABodyThatIsNotJson() {
    SimReply r = service.topup(req("T-6", "01712345678", "100.55"));
    assertThat(r.httpStatus()).isEqualTo(200);
    assertThat(r.body()).isInstanceOf(String.class);
    assertThat((String) r.body()).doesNotEndWith("}");
    assertThat(body(service.statusReply("T-6")).status()).isEqualTo("SUCCESS");
  }

  @Test
  void outageAnswers503AndDoesNotApplyTheTopup() {
    scenarios.set("ocs", Scenario.DEFAULT.withOutage(true));
    assertThat(service.topup(req("T-7", "01712345678", "100.00")).httpStatus()).isEqualTo(503);
    scenarios.reset("ocs");
    assertThat(service.statusReply("T-7").httpStatus()).isEqualTo(404);
  }

  @Test
  void errorRateIsComparedWithTheDraw() {
    nextRandom = 0.5;
    scenarios.set("ocs", Scenario.DEFAULT.withErrorRate(0.6));
    assertThat(service.topup(req("T-8", "01712345678", "100.00")).httpStatus()).isEqualTo(503);
    scenarios.set("ocs", Scenario.DEFAULT.withErrorRate(0.4));
    assertThat(service.topup(req("T-9", "01712345678", "100.00")).httpStatus()).isEqualTo(200);
  }

  @Test
  void latencyIsApplied() {
    scenarios.set("ocs", Scenario.DEFAULT.withLatencyMs(30));
    long t0 = System.nanoTime();
    service.topup(req("T-10", "01712345678", "100.00"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(25));
  }

  @Test
  void aRepeatedTransactionIdReturnsTheOriginalOutcomeWithoutReapplying() {
    OcsResponse first = body(service.topup(req("T-11", "01712345678", "100.00")));
    OcsResponse again = body(service.topup(req("T-11", "01712345678", "100.00")));
    assertThat(again.ocsRef()).isEqualTo(first.ocsRef());
    assertThat(ledger.size()).isEqualTo(1);
  }

  @Test
  void statusDuringAnOutageIsUnavailable() {
    scenarios.set("ocs", Scenario.DEFAULT.withOutage(true));
    assertThat(service.statusReply("T-x").httpStatus()).isEqualTo(503);
  }

  @Test
  void statusReplyIs404ForUnknownAnd200ForKnown() {
    service.topup(req("T-12", "01712345678", "100.00"));
    assertThat(service.statusReply("T-12").httpStatus()).isEqualTo(200);
    SimReply missing = service.statusReply("T-zzz");
    assertThat(missing.httpStatus()).isEqualTo(404);
    assertThat(body(missing).status()).isEqualTo("NOT_FOUND");
  }

  @Test
  void callbackIsSentWhenConfiguredAndDuplicatedOnRequest() {
    scenarios.set("ocs", Scenario.DEFAULT.withCallback(CallbackMode.DUPLICATE, "http://cb.test/x", 0));
    service.topup(req("T-13", "01712345678", "100.00"));
    assertThat(callbacks).hasSize(2);
    assertThat(callbacks.get(0)).startsWith("http://cb.test/x ").contains("\"transactionId\":\"T-13\"");
  }

  @Test
  void noCallbackByDefault() {
    service.topup(req("T-14", "01712345678", "100.00"));
    assertThat(callbacks).isEmpty();
  }

  @Test
  void forcedModeAppliesToEveryRequest() {
    scenarios.set("ocs", Scenario.DEFAULT.withForceMode(SimMode.BUSINESS_FAILURE));
    assertThat(service.topup(req("T-15", "01712345678", "100.00")).httpStatus()).isEqualTo(422);
  }
}
