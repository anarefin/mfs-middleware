package mfs.mw.simulators.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import mfs.mw.simulators.dto.Biller;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.BankC2wService;
import mfs.mw.simulators.service.BillAggregatorService;
import mfs.mw.simulators.service.CallbackService;
import mfs.mw.simulators.service.OcsService;
import mfs.mw.simulators.service.RequestRecorder;
import mfs.mw.simulators.service.ScenarioStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The simulators' HTTP surface (T3): each controller maps the wire contract to its service, passes
 * the service's status and body through unchanged, and refuses an invalid body with the error
 * envelope before the service is asked. Services are mocked; their behaviour has its own tests.
 */
@WebMvcTest({OcsController.class, BillAggregatorController.class, BankC2wController.class, ControlController.class})
class SimulatorControllersTest {

  private final MockMvcTester mvc;

  @MockitoBean OcsService ocs;
  @MockitoBean BillAggregatorService billagg;
  @MockitoBean BankC2wService bank;
  @MockitoBean ScenarioStore scenarios;
  @MockitoBean RequestRecorder recorder;
  @MockitoBean CallbackService callbacks;

  @Autowired
  SimulatorControllersTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  private MvcTestResult postJson(String uri, String body) {
    return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  // --- OCS -----------------------------------------------------------------------------------

  @Test
  void aTopupIsHandedToTheOcsServiceAndItsReplyPassesThrough() {
    when(ocs.topup(any())).thenReturn(new SimReply(422, Map.of("status", "FAILED", "code", "2001")));

    MvcTestResult r = postJson("/ocs/v1/topup", "{\"transactionId\":\"BITS1\",\"msisdn\":\"01711000001\",\"amount\":\"20.00\"}");

    assertThat(r).hasStatus(422);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("2001");
    verify(ocs).topup(new OcsTopupRequest("BITS1", "01711000001", "20.00"));
  }

  @Test
  void anInvalidTopupIsRefusedWithoutAskingTheService() {
    MvcTestResult r = postJson("/ocs/v1/topup", "{\"transactionId\":\"\",\"msisdn\":\"12345\",\"amount\":\"x\"}");

    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(ocs, never()).topup(any());
  }

  @Test
  void theOcsStatusInquiryAsksByReference() {
    when(ocs.statusReply("BITS2")).thenReturn(new SimReply(404, Map.of("status", "NOT_FOUND")));

    MvcTestResult r = mvc.get().uri("/ocs/v1/topup/BITS2").exchange();

    assertThat(r).hasStatus(404);
    assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("NOT_FOUND");
  }

  // --- bill aggregator -----------------------------------------------------------------------

  @Test
  void theAggregatorListsBillersAndPassesPaymentAndStatusReplies() {
    when(billagg.billers()).thenReturn(List.of(new Biller("DESCO", "Dhaka Electric", "ELECTRICITY")));
    when(billagg.pay(any())).thenReturn(new SimReply(200, Map.of("status", "SUCCESS", "paymentRef", "AGG-1")));
    when(billagg.status("BITS3")).thenReturn(new SimReply(200, Map.of("status", "SUCCESS")));

    assertThat(mvc.get().uri("/billagg/v1/billers").exchange()).bodyJson().extractingPath("$[0].code").isEqualTo("DESCO");
    MvcTestResult paid =
        postJson("/billagg/v1/payments", "{\"transactionId\":\"BITS3\",\"billerCode\":\"DESCO\",\"billNumber\":\"1234567\",\"amount\":\"50.00\"}");
    assertThat(paid).hasStatusOk();
    assertThat(paid).bodyJson().extractingPath("$.paymentRef").isEqualTo("AGG-1");
    assertThat(mvc.get().uri("/billagg/v1/payments/BITS3").exchange()).hasStatusOk();
  }

  @Test
  void anInvalidBillFetchIsRefusedWithoutAskingTheService() {
    MvcTestResult r = postJson("/billagg/v1/bills/fetch", "{\"billerCode\":\"desco!\",\"billNumber\":\"\"}");

    assertThat(r).hasStatus(400);
    verify(billagg, never()).fetch(any());
  }

  // --- C2W bank ------------------------------------------------------------------------------

  @Test
  void aDebitIsHandedToTheBankServiceAndAnInvalidAccountIsRefused() {
    when(bank.debit(any())).thenReturn(new SimReply(202, Map.of("status", "PENDING")));

    assertThat(postJson("/c2wbank/v1/debits", "{\"transactionId\":\"BITS4\",\"accountNumber\":\"1234567890123\",\"amount\":\"20.00\"}"))
        .hasStatus(202);
    assertThat(postJson("/c2wbank/v1/debits", "{\"transactionId\":\"BITS5\",\"accountNumber\":\"12ab\",\"amount\":\"20.00\"}"))
        .hasStatus(400);
    verify(bank).debit(any());
  }

  // --- control API ---------------------------------------------------------------------------

  @Test
  void theScenarioIsSetResetAndRequestsAreClearedPerPartner() {
    MvcTestResult set = postJson("/sim/ocs/scenario", "{\"latencyMs\":250,\"errorRate\":0.5,\"outage\":false,\"callbackDelayMs\":0}");
    assertThat(set).hasStatusOk();
    assertThat(set).bodyJson().extractingPath("$.latencyMs").isEqualTo(250);
    verify(scenarios).set(eq("ocs"), any());

    assertThat(mvc.delete().uri("/sim/ocs/scenario").exchange()).hasStatus(204);
    verify(scenarios).reset("ocs");

    assertThat(mvc.delete().uri("/sim/ocs/requests").exchange()).hasStatus(204);
    verify(recorder).clear("ocs");
  }

  @Test
  void aScenarioOutOfRangeIsRefused() {
    assertThat(postJson("/sim/ocs/scenario", "{\"latencyMs\":-1,\"errorRate\":2.0,\"callbackDelayMs\":0}")).hasStatus(400);
    verify(scenarios, never()).set(any(), any());
  }

  @Test
  void aCallbackReplayReportsTheTargetStatus() {
    when(callbacks.replay(eq("billagg"), eq("BITS6"), isNull())).thenReturn(200);

    MvcTestResult r = mvc.post().uri("/sim/billagg/callbacks/BITS6/replay").exchange();

    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.reference").isEqualTo("BITS6");
    assertThat(r).bodyJson().extractingPath("$.targetStatus").isEqualTo(200);
  }
}
