package mfs.mw.portal.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.portal.api.client.OrchestratorQueryClient;
import mfs.mw.portal.api.config.SecurityConfig;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.dto.Party;
import mfs.mw.portal.api.security.PortalUserResolver;
import mfs.mw.portal.api.service.SpPortalService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The SP portal (ticket 129d, FRS UC-20, CH-POR-04/05): an SP token only ever reaches its own org's
 * data. Whatever the query parameters say, the orchestrator is asked for the token's org, and another
 * org's transaction is 404 like an unknown one. Real controller and service; only the orchestrator's
 * query API is stubbed.
 */
@WebMvcTest(SpPortalController.class)
@Import({SecurityConfig.class, PortalUserResolver.class, SpPortalService.class})
class SpScopeTest {

  static final String OWN = "ORG001";
  static final String OTHER = "ORG002";
  static final UUID TXN = UUID.fromString("019a5b2e-1c00-7abc-8def-000000000002");
  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");

  private final MockMvcTester mvc;

  @MockitoBean OrchestratorQueryClient orchestrator;

  @Autowired
  SpScopeTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static JwtRequestPostProcessor sp(String... roles) {
    return jwt().jwt(j -> j.subject("sp-1").claim("orgId", OWN).claim("preferred_username", "sp-viewer01")
        .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static OrgTxn txn(String role, String reference) {
    return new OrgTxn(
        role, TXN.toString(), "BILL_PAY", "COMPLETED", AT, AT.plusSeconds(2), new Amount(150050L, "BDT"),
        new Amount(500L, "BDT"), new Party("WALLET", "0171****001", "CUSTOMER"), new Party("BILLER", "****0001", null),
        null, null, reference == null ? null : Map.of("reference", reference));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> searchedFilters() {
    ArgumentCaptor<Map<String, Object>> filters = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator).search(eq(OWN), filters.capture());
    return filters.getValue();
  }

  @Test
  void theSearchIsAlwaysForTheTokensOrgWhateverTheQuerySays() {
    when(orchestrator.search(eq(OWN), anyMap())).thenReturn(new OrgTxn.Page(List.of(txn("PAYEE", null)), 50, 0));

    MvcTestResult r =
        mvc.get().uri("/portal/sp/v1/transactions?orgId={o}&org={o}&initiator={o}&status=COMPLETED", OTHER, OTHER, OTHER)
            .with(sp("sp-viewer")).exchange();

    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.items[0].role").isEqualTo("PAYEE");
    verify(orchestrator, never()).search(eq(OTHER), anyMap());
    Map<String, Object> filters = searchedFilters();
    assertThat(filters).doesNotContainKeys("orgId", "org", "initiator").containsEntry("status", "COMPLETED");
  }

  @Test
  void anotherOrgsTransactionIs404LikeAnUnknownOne() {
    when(orchestrator.detail(OWN, TXN)).thenReturn(Optional.empty());

    MvcTestResult r = mvc.get().uri("/portal/sp/v1/transactions/{id}?orgId={o}", TXN, OTHER).with(sp("sp-viewer")).exchange();

    assertThat(r).hasStatus(404);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    verify(orchestrator).detail(OWN, TXN);
    verify(orchestrator, never()).detail(eq(OTHER), any());
  }

  @Test
  void theOwnTransactionShowsStateChangesOnly() {
    when(orchestrator.detail(OWN, TXN))
        .thenReturn(Optional.of(new OrgTxn.Detail(
            txn("PAYEE", null),
            List.of(new OrgTxn.Step("STATE", "RECEIVED", "POSTING", AT), new OrgTxn.Step("CORE", null, null, AT),
                new OrgTxn.Step("STATE", "POSTING", "COMPLETED", AT)))));

    MvcTestResult r = mvc.get().uri("/portal/sp/v1/transactions/{id}", TXN).with(sp("sp-viewer")).exchange();

    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.timeline.length()").isEqualTo(2);
  }

  @Test
  void theReportIsTheTokensOrgsTransactionsAsCsv() {
    when(orchestrator.search(eq(OWN), anyMap()))
        .thenReturn(new OrgTxn.Page(List.of(txn("PAYEE", "=HYPERLINK(\"x\")"), txn("INITIATOR", "INV,7")), 200, 0));

    MvcTestResult r =
        mvc.get().uri("/portal/sp/v1/reports?from=2026-11-01T00:00:00Z&to=2026-11-08T00:00:00Z&orgId={o}", OTHER)
            .with(sp("sp-viewer")).exchange();

    assertThat(r).hasStatusOk();
    assertThat(r).hasContentTypeCompatibleWith("text/csv");
    assertThat(r).headers().hasValue("Content-Disposition",
        "attachment; filename=\"sp-report-20261101T000000Z-20261108T000000Z.csv\"");
    assertThat(r).hasBodyTextEqualTo(
        "txnId,createdAt,txnType,role,status,amount,fee,debitPartyType,debitPartyId,creditPartyType,creditPartyId,"
            + "reference,errorCode\n"
            + TXN + ",2026-11-05T10:00:00Z,BILL_PAY,PAYEE,COMPLETED,1500.50,5.00,WALLET,0171****001,BILLER,****0001,"
            + "\"'=HYPERLINK(\"\"x\"\")\",\n"
            + TXN + ",2026-11-05T10:00:00Z,BILL_PAY,INITIATOR,COMPLETED,1500.50,5.00,WALLET,0171****001,BILLER,****0001,"
            + "\"INV,7\",\n");
    Map<String, Object> filters = searchedFilters();
    assertThat(filters).doesNotContainKey("orgId")
        .containsEntry("from", Instant.parse("2026-11-01T00:00:00Z"))
        .containsEntry("to", Instant.parse("2026-11-08T00:00:00Z"));
  }

  @Test
  void theReportPagesThroughTheRange() {
    List<OrgTxn> full = java.util.Collections.nCopies(200, txn("PAYEE", null));
    when(orchestrator.search(eq(OWN), anyMap()))
        .thenAnswer(inv -> {
          int offset = (int) ((Map<?, ?>) inv.getArgument(1)).get("offset");
          return new OrgTxn.Page(offset == 0 ? full : List.of(txn("PAYEE", null)), 200, offset);
        });

    MvcTestResult r =
        mvc.get().uri("/portal/sp/v1/reports?from=2026-11-01T00:00:00Z&to=2026-11-08T00:00:00Z")
            .with(sp("sp-viewer")).exchange();

    assertThat(r).hasStatusOk();
    assertThat(r.getResponse().getContentAsByteArray()).isNotEmpty();
    String body = new String(r.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body.lines().count()).isEqualTo(1 + 201);
  }

  @Test
  void aRangeOverThirtyOneDaysOrUpsideDownIs400AndNothingIsAsked() {
    for (String q : List.of(
        "from=2026-10-01T00:00:00Z&to=2026-11-02T00:00:00Z",
        "from=2026-11-08T00:00:00Z&to=2026-11-01T00:00:00Z",
        "to=2026-11-01T00:00:00Z")) {
      MvcTestResult r = mvc.get().uri("/portal/sp/v1/reports?" + q).with(sp("sp-viewer")).exchange();
      assertThat(r).as(q).hasStatus(400);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verifyNoInteractions(orchestrator);
  }

  @Test
  void aReportOverTheRowCapIs400() {
    List<OrgTxn> full = java.util.Collections.nCopies(200, txn("PAYEE", null));
    when(orchestrator.search(eq(OWN), anyMap()))
        .thenAnswer(inv -> new OrgTxn.Page(full, 200, (int) ((Map<?, ?>) inv.getArgument(1)).get("offset")));

    MvcTestResult r =
        mvc.get().uri("/portal/sp/v1/reports?from=2026-11-01T00:00:00Z&to=2026-11-08T00:00:00Z")
            .with(sp("sp-viewer")).exchange();

    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-040");
  }

  @Test
  void withoutTheSpViewerRoleNothingIsAsked() {
    for (String uri : List.of(
        "/portal/sp/v1/transactions", "/portal/sp/v1/transactions/" + TXN,
        "/portal/sp/v1/reports?from=2026-11-01T00:00:00Z&to=2026-11-08T00:00:00Z")) {
      MvcTestResult r = mvc.get().uri(uri).with(sp("org-maker", "org-checker")).exchange();
      assertThat(r).as(uri).hasStatus(403);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    }
    verifyNoInteractions(orchestrator);
  }

  @Test
  void aTokenWithoutAnOrgIs403AndNoTokenIs401() {
    MvcTestResult noOrg =
        mvc.get().uri("/portal/sp/v1/transactions?orgId={o}", OTHER)
            .with(jwt().jwt(j -> j.subject("sp-1").claim("realm_access", Map.of("roles", List.of("sp-viewer")))))
            .exchange();
    assertThat(noOrg).hasStatus(403);
    assertThat(noOrg).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    MvcTestResult none = mvc.get().uri("/portal/sp/v1/transactions?orgId={o}", OTHER).exchange();
    assertThat(none).hasStatus(401);
    verify(orchestrator, never()).search(anyString(), anyMap());
  }
}
