package mfs.mw.data.export.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import mfs.mw.data.export.TestInfrastructure;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The daily drop lands on SFTP with checksums the file bytes reproduce, and a second run uploads
 * nothing (ticket 128).
 */
@SpringBootTest
@Import({TestInfrastructure.class, DwDailyExportIT.Stubs.class})
class DwDailyExportIT {

  static final String IMAGE =
      "atmoz/sftp:alpine@sha256:c5fb9e4beb8b7054a4c0b65c5e11df47cc20da95ad6002e88aecff62bab06372";
  static final GenericContainer<?> SFTP = new GenericContainer<>(DockerImageName.parse(IMAGE))
      .withExposedPorts(22)
      .withCommand("bits:test-pass:1001::dw/in");

  static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void sftp(DynamicPropertyRegistry registry) {
    if (!SFTP.isRunning()) {
      SFTP.start();
    }
    registry.add("mw.export.sftp.host", SFTP::getHost);
    registry.add("mw.export.sftp.port", () -> String.valueOf(SFTP.getMappedPort(22)));
    registry.add("mw.export.sftp.username", () -> "bits");
    registry.add("mw.export.sftp.password", () -> "test-pass");
    registry.add("mw.export.sftp.directory", () -> "dw/in");
    registry.add("mw.export.sftp.insecure-host-key", () -> "true");
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    FieldTokeniser fieldTokeniser() {
      return (domain, value) -> "01712345678".equals(value) ? "MSISDN_PAYER" : "MSISDN_OTHER";
    }

    @Bean
    @Primary
    WalletSnapshotSource wallets() {
      return at -> java.util.List.of(new WalletSnapshotSource.Line("01712345678", "WALLET", "ACTIVE", 500, 0));
    }
  }

  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  DwDailyExportJob job;

  @Test
  void theDropMatchesItsControlAndASecondRunLeavesTheFileAlone() throws Exception {
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Dhaka"));
    jdbc.update(
        """
        insert into txn_fact (txn_id, txn_type, status, amount_minor, currency, channel,
          payer_token, payee_token, created_at, completed_at, business_date)
        values ('txn-128', 'P2P', 'COMPLETED', 100, 'BDT', 'APP', 'MSISDN_PAYER', 'MSISDN_PAYEE',
          now(), now(), ?)
        """,
        day);
    DwDailyExportJob.ExportResult first = job.export(day, "mw-e2e", false);
    assertThat(first.uploaded()).isTrue();
    byte[] txns = download("transactions_" + day + ".csv");
    byte[] wallets = download("wallet_snapshot_" + day + ".csv");
    byte[] control = download("control_" + day + ".json");
    assertThat(new String(txns, StandardCharsets.UTF_8)).contains(",100,BDT,").doesNotContain(",999,");
    assertThat(new String(wallets, StandardCharsets.UTF_8)).contains("MSISDN_PAYER").doesNotContain("01712345678");
    JsonNode listed = JSON.readTree(control).path("files");
    assertThat(listed).hasSize(4);
    Map<String, String> sha = new java.util.HashMap<>();
    sha.put("transactions_" + day + ".csv", sha256(txns));
    sha.put("wallet_snapshot_" + day + ".csv", sha256(wallets));
    for (JsonNode file : listed) {
      String name = file.path("name").asString();
      if (sha.containsKey(name)) {
        assertThat(file.path("sha256").asString()).isEqualTo(sha.get(name));
      }
      assertThat(download(name)).isNotEmpty();
    }
    jdbc.update("update txn_fact set amount_minor = 999 where txn_id = 'txn-128'");
    DwDailyExportJob.ExportResult second = job.export(day, "mw-e2e", false);
    assertThat(second.uploaded()).isFalse();
    assertThat(new String(download("transactions_" + day + ".csv"), StandardCharsets.UTF_8))
        .contains(",100,BDT,").doesNotContain(",999,");
  }

  static byte[] download(String name) throws Exception {
    Path local = Files.createTempFile("dw", ".bin");
    SSHClient ssh = new SSHClient();
    ssh.addHostKeyVerifier(new PromiscuousVerifier());
    ssh.connect(SFTP.getHost(), SFTP.getMappedPort(22));
    ssh.authPassword("bits", "test-pass");
    try (SFTPClient sftp = ssh.newSFTPClient()) {
      sftp.get("dw/in/" + name, local.toString());
    } finally {
      ssh.disconnect();
    }
    return Files.readAllBytes(local);
  }

  static String sha256(byte[] body) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  }
}
