package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.client.MsisdnTokeniser;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.TxnStatusEvent;
import mfs.mw.audit.dw.security.InternalTokens;
import mfs.mw.audit.dw.service.AuditService;
import mfs.mw.audit.dw.service.TxnIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 130 (FRS AUD-03): the audit search by the transaction's partner, channel, status, amount
 * range and a party's MSISDN, through the search projection {@code audit.txn_index} fed by {@code
 * mw.txn.status.v1} ({@link TxnIndexService}). The MSISDN is stored and searched only as a token
 * (a test-only tokeniser stands in for mw-crypto's TOKENISE_MSISDN).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"mw.audit-dw.consumers.enabled=false", "mw.audit-dw.query.tokenise-msisdn=true"})
@Import({TestInfrastructure.class, AuditSearchApiIT.TestClock.class, AuditSearchFiltersIT.FakeTokens.class})
class AuditSearchFiltersIT {

  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";

  /** Deterministic stand-in for TOKENISE_MSISDN (not a keyed hash; test only). */
  @TestConfiguration(proxyBeanMethods = false)
  static class FakeTokens {
    @Bean
    @Primary
    MsisdnTokeniser fakeTokeniser() {
      return values -> values.stream().map(AuditSearchFiltersIT::token).toList();
    }
  }

  static String token(String msisdn) {
    try {
      return "MSISDN_" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(msisdn.getBytes(StandardCharsets.UTF_8))).substring(0, 26).toUpperCase();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String P2P = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a01";
  private static final String TOPUP = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a02";
  private static final String BILL = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a03";

  private final AuditService audit;
  private final TxnIndexService index;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired
  AuditSearchFiltersIT(AuditService audit, TxnIndexService index, Clock clock, DataSource dataSource) {
    this.audit = audit;
    this.index = index;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
  }

  @BeforeEach
  void seed() {
    clock.set(AuditSearchApiIT.NOV);
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head, audit.payload, audit.txn_index");
    status(P2P, "CUSTOMER_APP", "P2P", "COMPLETED", 50_000, null, "01711234567", "01811000002", "2026-11-03T08:00:01Z");
    status(TOPUP, "USSD", "TOPUP", "COMPLETED", 2_000, "ocs", "01911000003", null, "2026-11-03T08:00:02Z");
    status(BILL, "CUSTOMER_APP", "BILL_PAY", "FAILED", 150_000, "billagg", "01711234567", null, "2026-11-03T08:00:03Z");
    for (String txn : List.of(P2P, TOPUP, BILL)) {
      record(txn, "txn.state.changed", "2026-11-03T08:00:01Z");
      record(txn, "txn.state.changed", "2026-11-03T08:00:04Z");
    }
  }

  private void status(
      String txn, String channel, String type, String status, long amount, String partner, String debit, String credit,
      String at) {
    index.apply(
        json.readValue(
            """
            {"schemaVersion":1,"txnId":"%s","correlationId":"c","channel":"%s","txnType":"%s","status":"%s",
             "debitParty":{"type":"WALLET","id":"%s"}%s,"amount":{"valueMinor":%d,"currency":"BDT"}%s,
             "requestedAt":"%s","updatedAt":"%s"}
            """
                .formatted(txn, channel, type, status, debit,
                    credit == null ? "" : ",\"creditParty\":{\"type\":\"WALLET\",\"id\":\"" + credit + "\"}",
                    amount,
                    partner == null ? "" : ",\"partner\":{\"partnerId\":\"" + partner + "\",\"productCode\":\"x\"}",
                    at, at),
            TxnStatusEvent.class));
  }

  private void record(String txnId, String action, String at) {
    audit.record(
        json.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-1",
             "actor":{"type":"system","id":"mw-orchestrator"},"action":"%s","resource":"txn/%s",
             "outcome":"SUCCESS","at":"%s","service":"mw-orchestrator"}
            """
                .formatted(UUID.randomUUID(), txnId, action, txnId, at),
            AuditEvent.class));
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .header("Authorization", InternalTokens.bearer("mw-admin-api"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private List<String> txns(String query) throws Exception {
    HttpResponse<String> res = get("/internal/v1/audit/events?" + query);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode page = json.readTree(res.body());
    return page.path("items").valueStream().map(n -> n.path("txnId").asString()).distinct().toList();
  }

  @Test
  void byPartnerChannelStatusAndAmountRange() throws Exception {
    assertThat(txns("partner=ocs")).containsExactly(TOPUP);
    assertThat(txns("channel=CUSTOMER_APP")).containsExactly(P2P, BILL);
    assertThat(txns("status=FAILED")).containsExactly(BILL);
    assertThat(txns("amountMin=10000&amountMax=100000")).containsExactly(P2P);
    assertThat(txns("channel=CUSTOMER_APP&amountMin=100000")).containsExactly(BILL);
    assertThat(txns("partner=billagg&from=2026-11-03T08:00:02Z")).containsExactly(BILL);
    assertThat(get("/internal/v1/audit/events?partner=ocs").body()).contains("\"hop\":\"ORCHESTRATOR\"");
  }

  @Test
  void byMsisdnInAnyFormThroughItsTokenOnly() throws Exception {
    assertThat(txns("msisdn=01711234567")).containsExactly(P2P, BILL);
    assertThat(txns("msisdn=%2B8801711234567")).containsExactly(P2P, BILL);
    assertThat(txns("msisdn=01811000002")).containsExactly(P2P);
    assertThat(txns("msisdn=01511000009")).isEmpty();
    // the projection holds tokens, never the number
    assertThat(admin.queryForObject("select count(*) from audit.txn_index where msisdn_tokens::text like '%0171%'", Long.class))
        .isZero();
    assertThat(get("/internal/v1/audit/events?msisdn=12345678901").statusCode()).isEqualTo(400);
  }

  @Test
  void aLateOlderStatusDoesNotOverwriteTheNewest() throws Exception {
    status(BILL, "CUSTOMER_APP", "BILL_PAY", "PARTNER_PENDING", 150_000, "billagg", "01711234567", null, "2026-11-03T07:59:00Z");

    assertThat(txns("status=FAILED")).containsExactly(BILL);
  }

  @Test
  void aBadAmountRangeIsRefused() throws Exception {
    HttpResponse<String> res = get("/internal/v1/audit/events?amountMin=500&amountMax=100");
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("MW-VAL-001");
  }
}
