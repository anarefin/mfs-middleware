package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.service.AuditChainVerifier;
import mfs.mw.audit.dw.service.AuditSearchService;
import mfs.mw.audit.dw.service.ChainVerification;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Test-only audit API (ticket 58): the Newman suite reads a batch's audit trail and verifies a
 * month's hash chain without psql and without a services-realm token.
 */
@WebMvcTest(
    controllers = AuditTestController.class,
    properties = {"mw.audit-dw.test-api.enabled=true", "spring.test.mockmvc.print=none"})
class AuditTestControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean AuditSearchService search;
  @MockitoBean AuditChainVerifier verifier;

  @Autowired
  AuditTestControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void searchesEventsByResourceAsTheInternalApiDoes() {
    AuditEntry e =
        new AuditEntry(
            UUID.randomUUID().toString(), "2026-11", 7L, Instant.parse("2026-11-06T10:00:00Z"),
            Instant.parse("2026-11-06T10:00:00Z"), null, "corr-1", new AuditEvent.Actor("user", "org-checker01"),
            "bulk.batch.approved", "bulk-batch/b1", "SUCCESS", "mw-portal-api", null, null, null, null);
    when(search.search(any())).thenReturn(new AuditEntry.Page(List.of(e), 100, 0));

    assertThat(mvc.get().uri("/test/v1/audit/events?resource=bulk-batch/b1"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.items[0].action")
        .isEqualTo("bulk.batch.approved");

    ArgumentCaptor<AuditSearchCriteria> c = ArgumentCaptor.forClass(AuditSearchCriteria.class);
    verify(search).search(c.capture());
    assertThat(c.getValue().resource()).isEqualTo("bulk-batch/b1");
    assertThat(c.getValue().limit()).isEqualTo(100);
  }

  @Test
  void aLimitOutsideTheRangeIsTheValidationEnvelope() {
    assertThat(mvc.get().uri("/test/v1/audit/events?limit=501"))
        .hasStatus(400)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("MW-VAL-001");
  }

  @Test
  void verifiesTheChainOfOneMonth() {
    when(verifier.verify("2026-11")).thenReturn(new ChainVerification("2026-11", true, 42, null, null));

    var r = assertThat(mvc.get().uri("/test/v1/audit/chains/2026-11")).hasStatusOk().bodyJson();
    r.extractingPath("$.month").isEqualTo("2026-11");
    r.extractingPath("$.intact").isEqualTo(true);
    r.extractingPath("$.rows").isEqualTo(42);
  }

  @Test
  void aBrokenChainNamesTheFirstBadSeq() {
    when(verifier.verify("2026-10"))
        .thenReturn(new ChainVerification("2026-10", false, 9, 4L, "hash does not match the row"));

    var r = assertThat(mvc.get().uri("/test/v1/audit/chains/2026-10")).hasStatusOk().bodyJson();
    r.extractingPath("$.intact").isEqualTo(false);
    r.extractingPath("$.failedSeq").isEqualTo(4);
    r.extractingPath("$.reason").isEqualTo("hash does not match the row");
  }

  @Test
  void aMonthThatIsNotYyyyMmIsRefused() {
    assertThat(mvc.get().uri("/test/v1/audit/chains/2026-1x"))
        .hasStatus(400)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("MW-VAL-001");
  }
}
