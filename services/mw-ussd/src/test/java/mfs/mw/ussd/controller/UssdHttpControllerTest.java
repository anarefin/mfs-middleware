package mfs.mw.ussd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.transport.HttpUssdTransport;
import mfs.mw.ussd.service.UssdEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The MNO-facing HTTP transport: wire format, per-MNO flags, refusals. */
@WebMvcTest(UssdHttpController.class)
@Import(HttpUssdTransport.class)
@EnableConfigurationProperties(UssdProperties.class)
class UssdHttpControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean UssdEngine engine;

  @Autowired
  UssdHttpControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String mno, String body) {
    return mvc.post().uri("/ussd/" + mno).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  @Test
  void aStepIsHandedToTheEngineAndAnsweredWithTheScreenAndTheContinueFlag() {
    when(engine.handle(any())).thenReturn(new UssdResponse("s-1", "Bits\n1 Send Money", false));

    MvcTestResult r =
        post("robi", "{\"sessionId\":\"s-1\",\"msisdn\":\"8801712345678\",\"serviceCode\":\"*268#\",\"input\":\"\",\"type\":\"BEGIN\"}");

    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.message").isEqualTo("Bits\n1 Send Money");
    assertThat(r).bodyJson().extractingPath("$.flag").isEqualTo("CON");
    assertThat(r).bodyJson().extractingPath("$.sessionId").isEqualTo("s-1");
    ArgumentCaptor<UssdRequest> req = ArgumentCaptor.forClass(UssdRequest.class);
    verify(engine).handle(req.capture());
    assertThat(req.getValue())
        .isEqualTo(new UssdRequest("robi", "s-1", "01712345678", UssdEvent.BEGIN, ""));
  }

  @Test
  void theLastScreenCarriesTheEndFlag() {
    when(engine.handle(any())).thenReturn(new UssdResponse("s-1", "Done", true));

    MvcTestResult r = post("robi", "{\"sessionId\":\"s-1\",\"msisdn\":\"01712345678\",\"input\":\"1\",\"type\":\"CONTINUE\"}");

    assertThat(r).bodyJson().extractingPath("$.flag").isEqualTo("END");
  }

  @Test
  void anAbortEndsTheSession() {
    when(engine.handle(any())).thenReturn(new UssdResponse("s-1", "", true));

    MvcTestResult r = post("robi", "{\"sessionId\":\"s-1\",\"msisdn\":\"01712345678\",\"type\":\"ABORT\"}");

    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.flag").isEqualTo("END");
  }

  @Test
  void anMnoNotOnHttpOrUnknownIsNotFound() {
    assertThat(post("gp", "{\"sessionId\":\"s\",\"msisdn\":\"01712345678\",\"type\":\"BEGIN\"}"))
        .hasStatus(HttpStatus.NOT_FOUND)
        .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    assertThat(post("acme", "{\"sessionId\":\"s\",\"msisdn\":\"01712345678\",\"type\":\"BEGIN\"}"))
        .hasStatus(HttpStatus.NOT_FOUND);
    verify(engine, never()).handle(any());
  }

  @Test
  void aBadNumberOrBodyIsRefused() {
    assertThat(post("robi", "{\"sessionId\":\"s\",\"msisdn\":\"12345\",\"type\":\"BEGIN\"}"))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(post("robi", "{\"msisdn\":\"01712345678\",\"type\":\"BEGIN\"}"))
        .hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post("robi", "{\"sessionId\":\"s\",\"msisdn\":\"01712345678\",\"type\":\"JUMP\"}"))
        .hasStatus(HttpStatus.BAD_REQUEST);
    verify(engine, never()).handle(any());
  }

  static final String STEP = "{\"sessionId\":\"s-1\",\"msisdn\":\"01712345678\",\"input\":\"1\",\"type\":\"CONTINUE\"}";

  /**
   * The contract-derived schema runs on the received bytes before binding (APISIX validates no
   * body, review A6): duplicate keys, trailing content, wrong types and non-objects are 400
   * MW-VAL-001 and the engine never sees the step.
   */
  @Test
  void aBodyBreakingTheSchemaIsRefusedBeforeTheEngine() {
    for (String body :
        new String[] {
          STEP.replace("\"input\":\"1\"", "\"input\":\"1\",\"input\":\"2\""),
          STEP.replace("\"type\":\"CONTINUE\"", "\"type\":\"CONTINUE\",\"type\":\"ABORT\""),
          STEP + " {}",
          STEP.replace("\"input\":\"1\"", "\"input\":1"),
          STEP.replace("\"input\":\"1\"", "\"input\":\"" + "1".repeat(183) + "\""),
          STEP.replace("\"s-1\"", "\"\""),
          "[]",
          "{\"sessionId\":",
        }) {
      assertThat(post("robi", body)).as(body).hasStatus(HttpStatus.BAD_REQUEST)
          .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(engine, never()).handle(any());
  }

  /** {@code input} may be a PIN: a schema error never repeats it. */
  @Test
  void aSchemaErrorDoesNotEchoTheInput() {
    MvcTestResult r = post("robi", STEP.replace("\"input\":\"1\"", "\"input\":{\"pin\":\"97531\"}"));

    assertThat(r).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(r.getResponse().getContentAsByteArray()).asString().doesNotContain("97531");
  }

  @Test
  void anOversizedBodyIsRefusedWith413() {
    MvcTestResult r = post("robi", STEP.replace("\"s-1\"", "\"" + "s".repeat(70_000) + "\""));

    assertThat(r).hasStatus(413).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-BODY_TOO_LARGE");
    verify(engine, never()).handle(any());
  }
}
