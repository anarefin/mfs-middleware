package mfs.mw.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import mfs.mw.orchestrator.dto.TestReversalAccepted;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.service.TestApiService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The test-only API (ticket 58, contracts/api/mw-orchestrator/orchestrator-test.v1.openapi.yaml):
 * only with {@code mw.orchestrator.test-api.enabled=true}.
 */
class TestApiControllerTest {

  private static final UUID ORIGINAL = UUID.fromString("0190a7c4-0000-7000-8000-000000000001");

  @Nested
  @WebMvcTest(controllers = TestApiController.class, properties = "mw.orchestrator.test-api.enabled=true")
  class SwitchedOn {

    private final MockMvcTester mvc;
    @MockitoBean TestApiService service;

    @Autowired
    SwitchedOn(MockMvcTester mvc) {
      this.mvc = mvc;
    }

    private MockMvcTester.MockMvcRequestBuilder reverse(String body) {
      return mvc.post().uri("/test/v1/reversals").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aReversalIsPublishedAnd202WithItsIds() {
      given(service.reverse(ORIGINAL, "chargeback"))
          .willReturn(
              new TestReversalAccepted(
                  "0190a7c4-0000-7000-8000-0000000000aa", "test-rev-0190a7c4-0000-7000-8000-0000000000bb"));

      assertThat(reverse("{\"originalTxnId\":\"" + ORIGINAL + "\",\"reason\":\"chargeback\"}"))
          .hasStatus(202)
          .bodyJson()
          .isLenientlyEqualTo(
              "{\"txnId\":\"0190a7c4-0000-7000-8000-0000000000aa\","
                  + "\"idempotencyKey\":\"test-rev-0190a7c4-0000-7000-8000-0000000000bb\"}");
    }

    @Test
    void anUnknownOriginalIsThe404Envelope() {
      given(service.reverse(any(), any()))
          .willThrow(new MwException(ErrorCodes.VAL_NOT_FOUND, "Transaction not found").withStatus(HttpStatus.NOT_FOUND));

      assertThat(reverse("{\"originalTxnId\":\"" + ORIGINAL + "\"}"))
          .hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    }

    @Test
    void aBadBodyIs400AndPublishesNothing() {
      assertThat(reverse("{}")).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
      assertThat(reverse("{\"originalTxnId\":\"not-a-uuid\"}")).hasStatus(400);
      verifyNoInteractions(service);
    }

    @Test
    void aHandledKeyIs200() {
      given(service.handled("test-rev-1")).willReturn(true);

      assertThat(mvc.get().uri("/test/v1/txn-requests/test-rev-1"))
          .hasStatus(200)
          .bodyJson()
          .isLenientlyEqualTo("{\"idempotencyKey\":\"test-rev-1\",\"handled\":true}");
    }

    @Test
    void aKeyNotSeenYetIs404() {
      given(service.handled("test-rev-2")).willReturn(false);

      assertThat(mvc.get().uri("/test/v1/txn-requests/test-rev-2"))
          .hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    }
  }

  @Nested
  @WebMvcTest(controllers = TestApiController.class)
  class SwitchedOff {

    private final MockMvcTester mvc;

    @Autowired
    SwitchedOff(MockMvcTester mvc) {
      this.mvc = mvc;
    }

    @Test
    void thereIsNoTestApiAtAll() {
      assertThat(mvc.post().uri("/test/v1/reversals").contentType(MediaType.APPLICATION_JSON)
              .content("{\"originalTxnId\":\"" + ORIGINAL + "\"}"))
          .hasStatus(404);
      assertThat(mvc.get().uri("/test/v1/txn-requests/k")).hasStatus(404);
    }
  }
}
