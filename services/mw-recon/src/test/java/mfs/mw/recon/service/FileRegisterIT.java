package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.contracts.ContractSchemas;
import mfs.mw.recon.dto.PartnerReconEvent;
import mfs.mw.recon.dto.RunSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A file is processed once (REC-08). Reprocess runs it again and writes an audit event. A
 * redelivered settlement line does not open a second exception (ticket 126).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class FileRegisterIT {

  static final String FIN = """
      {1:F01BITSBDDHXXXX0000000000}{2:I940TRUSTBDDXXXXN}{4:
      :20:HAND940
      :25:0011223344556
      :28C:324/1
      :60F:C261120BDT1000,00
      :61:2611201120D100,50NTRF0000800000000009//TB9
      FEE
      :86:0196a1b2-0000-7000-8000-000000000009
      :62F:C261120BDT899,50
      -}""";

  private final ReconService recon;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  FileRegisterIT(ReconService recon, JdbcTemplate jdbc, JsonMapper json) {
    this.recon = recon;
    this.jdbc = jdbc;
    this.json = json;
  }

  @Test
  void theSameFileIsProcessedOnceAndReprocessIsAudited() throws Exception {
    byte[] body = FIN.getBytes(StandardCharsets.UTF_8);
    RunSummary first = recon.ingestStatement("MT940_register.fin", body, true);
    RunSummary second = recon.ingestStatement("MT940_register.fin", body, true);

    assertThat(second.skipped()).isTrue();
    assertThat(recon.runs(UUID.fromString(first.fileId()))).isEqualTo(1);

    RunSummary again = recon.reprocess(UUID.fromString(first.fileId()), "seed-ops-maker");
    assertThat(recon.runs(UUID.fromString(first.fileId()))).isEqualTo(2);
    assertThat(again.skipped()).isFalse();
    assertThat(recon.exceptions(UUID.fromString(first.fileId()))).isEqualTo(1);

    String audit = jdbc.queryForObject(
        "select payload::text from outbox where topic = ? and payload->>'resource' = ?",
        String.class, ReconTopics.AUDIT, "source_file/" + first.fileId());
    Schema schema = ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json");
    assertThat(schema.validate(json.readTree(audit))).isEmpty();
  }

  @Test
  void aRedeliveredLineCreatesNoSecondException() {
    PartnerReconEvent event = new PartnerReconEvent(1, "bill126b", "billpay", "again.csv",
        "again.csv-10-1", 1, true,
        Map.of("partnerRef", "NO-SUCH", "amount", "10.00", "status", "SUCCESS", "paidAt", "2026-11-20"),
        "mw-adapter-billpay", Instant.parse("2026-11-20T06:00:00Z"));
    String key = "recon/bill126b/again.csv-10-1#1";
    assertThat(recon.onPartnerLine(event, key)).isTrue();
    assertThat(recon.onPartnerLine(event, key)).isFalse();

    UUID fileId = jdbc.queryForObject("select id from source_file where filename = ?", UUID.class, "again.csv");
    assertThat(recon.partnerLines(fileId)).isEqualTo(1);
    assertThat(recon.exceptions(fileId)).isEqualTo(1);
    assertThat(recon.runs(fileId)).isEqualTo(1);
  }
}
