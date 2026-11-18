package mfs.mw.simulators.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.simulators.config.ClockConfig;
import mfs.mw.simulators.service.PartnerStubStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The generic programmable partner stub (ticket 83): a canned answer per partner and operation
 * (method + path, {@code *} for one path segment), set through the control API {@code
 * /sim/stubs/<partnerId>}, served under {@code /sim/partners/<partnerId>/**} (the partner route
 * dry-runs and the tester console), every request recorded.
 */
@WebMvcTest(PartnerStubController.class)
@Import({PartnerStubStore.class, ClockConfig.class})
class PartnerStubControllerTest {

  private final MockMvcTester mvc;

  @Autowired
  PartnerStubControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  private MvcTestResult send(String method, String uri, String body) {
    var request = mvc.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri);
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return request.exchange();
  }

  @Test
  void aStubbedOperationIsAnsweredWithItsCannedResponseAndTheRequestIsRecorded() {
    assertThat(send("POST", "/sim/stubs/newco", """
        {"operation": "PAY", "method": "POST", "path": "/v1/payments", "status": 200,
         "body": {"receipt": "NC-1", "result": "OK"}}""")).hasStatus(200);
    assertThat(send("POST", "/sim/stubs/newco", """
        {"operation": "STATUS_QUERY", "method": "GET", "path": "/v1/payments/*", "status": 404, "body": {"code": "NF"}}"""))
        .hasStatus(200);

    MvcTestResult pay = send("POST", "/sim/partners/newco/v1/payments", "{\"ref\":\"DRYRUN-1\"}");
    MvcTestResult status = send("GET", "/sim/partners/newco/v1/payments/BITS-1", null);

    assertThat(pay).hasStatus(200).bodyJson().extractingPath("$.receipt").isEqualTo("NC-1");
    assertThat(status).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("NF");
    assertThat(send("GET", "/sim/stubs/newco/requests", null)).hasStatus(200).bodyJson()
        .extractingPath("$[0].body").isEqualTo("{\"ref\":\"DRYRUN-1\"}");
    assertThat(send("GET", "/sim/stubs/newco", null)).hasStatus(200).bodyJson()
        .extractingPath("$.length()").isEqualTo(2);
  }

  @Test
  void anOperationWithoutAStubIsA404AndAResetForgetsTheStubs() {
    send("POST", "/sim/stubs/other", "{\"method\": \"POST\", \"path\": \"/pay\", \"status\": 200, \"body\": {}}");

    assertThat(send("POST", "/sim/partners/other/refund", "{}")).hasStatus(404);
    assertThat(send("DELETE", "/sim/stubs/other", null)).hasStatus(204);
    assertThat(send("POST", "/sim/partners/other/pay", "{}")).hasStatus(404);
  }

  @Test
  void aStubWithoutPathOrWithAnUnknownMethodIsRefused() {
    assertThat(send("POST", "/sim/stubs/newco", "{\"method\": \"TRACE\", \"path\": \"/x\", \"status\": 200}")).hasStatus(400);
    assertThat(send("POST", "/sim/stubs/newco", "{\"method\": \"POST\", \"status\": 200}")).hasStatus(400);
  }
}
