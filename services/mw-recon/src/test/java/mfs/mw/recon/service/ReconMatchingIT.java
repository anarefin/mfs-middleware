package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.Map;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.contracts.ContractSchemas;
import mfs.mw.recon.dto.CoreLedgerEvent;
import mfs.mw.recon.dto.PartnerReconEvent;
import mfs.mw.recon.dto.TxnStatusEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One settlement file with a match, a missing partner line, a line Upay does not know, an amount
 * gap and a status gap publishes one {@code mw.recon.result.v1} (ticket 126).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ReconMatchingIT {

  static final Instant AT = Instant.parse("2026-11-20T06:00:00Z");
  static final String PARTNER = "bill126";
  static final String MATCHED = "0196a1b2-0000-7000-8000-000000000111";
  static final String AMOUNT = "0196a1b2-0000-7000-8000-000000000112";
  static final String REVERSED = "0196a1b2-0000-7000-8000-000000000113";
  static final String ABSENT = "0196a1b2-0000-7000-8000-000000000114";

  private final ReconService recon;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  ReconMatchingIT(ReconService recon, JdbcTemplate jdbc, JsonMapper json) {
    this.recon = recon;
    this.jdbc = jdbc;
    this.json = json;
  }

  @Test
  void oneFileYieldsEveryClassAndOneResult() throws Exception {
    project(MATCHED, "REF-OK", "COMPLETED");
    project(AMOUNT, "REF-AMT", "COMPLETED");
    project(REVERSED, "REF-REV", "REVERSED");
    project(ABSENT, "REF-GONE", "COMPLETED");
    post(MATCHED);
    post(AMOUNT);
    post(REVERSED);
    post(ABSENT);

    line(1, "REF-OK", "500.00", "SUCCESS", false);
    line(2, "REF-AMT", "501.01", "SUCCESS", false);
    line(3, "REF-REV", "500.00", "SUCCESS", false);
    line(4, "REF-EXTRA", "10.00", "SUCCESS", true);

    String payload = jdbc.queryForObject(
        "select payload::text from outbox where topic = ? and msg_key = ?",
        String.class, ReconTopics.RESULT, PARTNER);
    JsonNode body = json.readTree(payload);
    assertThat(body.path("counts").path("MATCHED").asInt()).isEqualTo(1);
    assertThat(body.path("counts").path("MISSING_IN_PARTNER").asInt()).isEqualTo(1);
    assertThat(body.path("counts").path("MISSING_IN_UPAY").asInt()).isEqualTo(1);
    assertThat(body.path("counts").path("AMOUNT_MISMATCH").asInt()).isEqualTo(1);
    assertThat(body.path("counts").path("STATUS_MISMATCH").asInt()).isEqualTo(1);
    Schema schema = ContractSchemas.schema("events/mw-recon/mw.recon.result.v1.schema.json");
    assertThat(schema.validate(body)).isEmpty();

    java.util.UUID fileId = jdbc.queryForObject(
        "select id from source_file where filename = ?", java.util.UUID.class, "settlement-126.csv");
    assertThat(recon.exceptions(fileId)).isEqualTo(4);
  }

  private void project(String txnId, String partnerRef, String status) {
    recon.onTxn(new TxnStatusEvent(txnId, "P2P", status,
        new TxnStatusEvent.Partner(PARTNER), partnerRef, "CORE-" + partnerRef,
        new TxnStatusEvent.Amount(50_000L, "BDT"), AT, AT),
        txnId + "|" + status);
  }

  private void post(String txnId) {
    recon.onLedger(new CoreLedgerEvent("TransferPosted", java.util.UUID.randomUUID().toString(),
        txnId, "CORE", AT, "SUCCESS"));
  }

  private void line(int n, String partnerRef, String amount, String status, boolean last) {
    PartnerReconEvent event = new PartnerReconEvent(1, PARTNER, "billpay", "settlement-126.csv",
        "settlement-126.csv-80-1", n, last,
        Map.of("partnerRef", partnerRef, "reference", partnerRef, "amount", amount, "status", status,
            "paidAt", "2026-11-20"),
        "mw-adapter-billpay", AT);
    recon.onPartnerLine(event, "recon/" + PARTNER + "/settlement-126.csv-80-1#" + n);
  }
}
