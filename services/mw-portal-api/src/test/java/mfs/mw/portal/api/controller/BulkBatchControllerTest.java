package mfs.mw.portal.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import mfs.mw.portal.api.config.SecurityConfig;
import mfs.mw.portal.api.security.PortalUserResolver;
import mfs.mw.portal.api.service.BatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Auth and request shape of the org bulk API (spec 8.2, 9.4): envelope codes, nothing reaches the service. */
@WebMvcTest(BulkBatchController.class)
@Import({SecurityConfig.class, PortalUserResolver.class})
class BulkBatchControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean BatchService service;

  @Autowired
  BulkBatchControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void noTokenIs401() {
    MvcTestResult r =
        mvc.post().uri("/portal/org/v1/bulk/batches").contentType("text/csv").content("msisdn,amount,reference\n").exchange();
    assertThat(r).hasStatus(401);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    assertThat(mvc.get().uri("/portal/org/v1/bulk/batches").exchange()).hasStatus(401);
    verifyNoInteractions(service);
  }

  @Test
  void aTokenWithoutAnOrgIs403() {
    MvcTestResult r =
        mvc.get()
            .uri("/portal/org/v1/bulk/batches")
            .with(jwt().jwt(j -> j.subject("u1").claim("realm_access", Map.of("roles", List.of("org-checker")))))
            .exchange();
    assertThat(r).hasStatus(403);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verifyNoInteractions(service);
  }

  @Test
  void anUploadMustBeCsv() {
    MvcTestResult r =
        mvc.post()
            .uri("/portal/org/v1/bulk/batches")
            .with(jwt().jwt(j -> j.subject("u1").claim("orgId", "ORG001")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .exchange();
    assertThat(r).hasStatus(415);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-005");
    verify(service, never()).upload(any(), any());
  }

  MvcTestResult reject(String body) {
    return mvc.post()
        .uri("/portal/org/v1/bulk/batches/{id}/reject", "b-1")
        .with(jwt().jwt(j -> j.subject("u2").claim("orgId", "ORG001")
            .claim("realm_access", Map.of("roles", List.of("org-checker")))))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  @Test
  void aValidRejectBodyReachesTheService() {
    assertThat(reject("{\"reason\":\"wrong month\"}")).hasStatusOk();
    verify(service).reject(any(), eq("b-1"), eq("wrong month"));
  }

  /** The contract-derived schema runs on the received bytes before binding (A6): 400 MW-VAL-001. */
  @Test
  void aRejectBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body :
        new String[] {
          "{}", "{\"reason\":\"\"}", "{\"reason\":7}", "{\"reason\":\"" + "x".repeat(201) + "\"}",
          "{\"reason\":\"a\",\"note\":\"b\"}", "{\"reason\":\"a\",\"reason\":\"b\"}", "{\"reason\":\"a\"} {}", "[]", "{",
        }) {
      assertThat(reject(body)).as(body).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(service, never()).reject(any(), any(), any());
  }

  @Test
  void anOversizedRejectBodyIsRefusedWith413() {
    MvcTestResult r = reject("{\"reason\":\"" + "x".repeat(70_000) + "\"}");
    assertThat(r).hasStatus(413).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-BODY_TOO_LARGE");
    verify(service, never()).reject(any(), any(), any());
  }
}
