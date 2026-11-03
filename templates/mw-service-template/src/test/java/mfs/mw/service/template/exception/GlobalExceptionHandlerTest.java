package mfs.mw.service.template.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Every failure becomes the 5.3 envelope; no stack trace or internal message leaks (18.6). */
@WebMvcTest
@Import(GlobalExceptionHandlerTest.FailingController.class)
class GlobalExceptionHandlerTest {

  @Autowired MockMvcTester mvc;

  record Transfer(@NotBlank String to, @Positive long amountMinor) {}

  @RestController
  static class FailingController {
    @GetMapping("/test/core")
    void core() {
      throw new MwException("MW-CORE-INSUFFICIENT-BAL", "Insufficient balance")
          .withTxnId("0190a7c4-0000-7000-8000-000000000001");
    }

    @GetMapping("/test/auth")
    void auth() {
      throw new MwException("MW-AUTH-001", "Unauthorised");
    }

    @GetMapping("/test/timeout")
    void timeout() {
      throw new MwException("MW-TMO-001", "Outcome unknown");
    }

    @GetMapping("/test/bug")
    void bug() {
      throw new IllegalStateException("secret internals: db password=hunter2");
    }

    @PostMapping("/test/transfer")
    void transfer(@Valid @RequestBody Transfer t) {}
  }

  MvcTestResult get(String path) {
    return mvc.get().uri(path).header("X-Correlation-Id", "corr-1").exchange();
  }

  @Test
  void mwExceptionMapsToItsNamespace() {
    MvcTestResult r = get("/test/core");
    assertThat(r).hasStatus(422);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-CORE-INSUFFICIENT-BAL");
    assertThat(r).bodyJson().extractingPath("$.message").isEqualTo("Insufficient balance");
    assertThat(r).bodyJson().extractingPath("$.txnId").isEqualTo("0190a7c4-0000-7000-8000-000000000001");
    assertThat(r).bodyJson().extractingPath("$.correlationId").isEqualTo("corr-1");
    assertThat(r).bodyJson().extractingPath("$.retryable").isEqualTo(false);
    assertThat(r).bodyJson().extractingPath("$.details").asArray().isEmpty();
  }

  @Test
  void authAndTimeoutNamespaces() {
    assertThat(get("/test/auth")).hasStatus(401);
    MvcTestResult tmo = get("/test/timeout");
    assertThat(tmo).hasStatus(504);
    assertThat(tmo).bodyJson().extractingPath("$.retryable").isEqualTo(true);
    assertThat(tmo).bodyJson().doesNotHavePath("$.txnId");
    assertThat(tmo).bodyJson().doesNotHavePath("$.messageBn");
  }

  @Test
  void beanValidationBecomesMwValWithFieldDetails() {
    MvcTestResult r =
        mvc.post()
            .uri("/test/transfer")
            .header("X-Correlation-Id", "corr-2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"to\":\"\",\"amountMinor\":-5}")
            .exchange();
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(r).bodyJson().extractingPath("$.correlationId").isEqualTo("corr-2");
    assertThat(r)
        .bodyJson()
        .extractingPath("$.details[*].field")
        .asArray()
        .containsExactlyInAnyOrder("to", "amountMinor");
    assertThat(r).bodyJson().extractingPath("$.details[*].issue").asArray().doesNotContainNull();
  }

  @Test
  void malformedBodyIsMwVal() {
    MvcTestResult r =
        mvc.post()
            .uri("/test/transfer")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json")
            .exchange();
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-002");
  }

  @Test
  void unknownPathIsMwVal404() {
    MvcTestResult r = get("/test/nope");
    assertThat(r).hasStatus(404);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Test
  void wrongMethodIsMwVal405() {
    MvcTestResult r = mvc.delete().uri("/test/core").exchange();
    assertThat(r).hasStatus(405);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-004");
  }

  @Test
  void unexpectedExceptionIsMwSysWithoutInternals() throws Exception {
    MvcTestResult r = get("/test/bug");
    assertThat(r).hasStatus(500);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-001");
    assertThat(r).bodyJson().extractingPath("$.retryable").isEqualTo(true);
    assertThat(r).bodyJson().extractingPath("$.correlationId").isEqualTo("corr-1");
    assertThat(r.getResponse().getContentAsString())
        .doesNotContain("hunter2")
        .doesNotContain("IllegalStateException")
        .doesNotContain("at mfs.");
  }
}
