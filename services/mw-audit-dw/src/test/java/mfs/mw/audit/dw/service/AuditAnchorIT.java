package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSObject;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.client.CryptoStub;
import mfs.mw.audit.dw.dto.AnchorStatus;
import mfs.mw.audit.dw.entity.AuditRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed daily anchor (ticket 74, spec 14.3): one anchor per UTC day over the chain heads
 * written that day, signed at mw-crypto (a stub with its own EC key), chained to the previous
 * anchor, stored INSERT-only and exported; and its verification, which catches what the hash chain
 * alone cannot (a chain rewritten consistently by a superuser) and a tampered or missing anchor.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "mw.audit-dw.consumers.enabled=false")
@Import({TestInfrastructure.class, AuditTrailIT.TestClock.class})
class AuditAnchorIT {

  private static final CryptoStub CRYPTO = CryptoStub.start();
  private static final Path EXPORT = tempDir();
  private static final String ZEROS = "0".repeat(64);
  /** Same test-only value as the test config; not a real secret. */
  private static final String FAKE_WRITER_PASSWORD = "test-only-writer-pw";

  private static final Instant NOV_3 = Instant.parse("2026-11-03T08:15:31.123456Z");
  private static final Instant NOV_4 = Instant.parse("2026-11-04T23:59:59.000001Z");
  private static final Instant NOV_5 = Instant.parse("2026-11-05T00:15:00Z");

  @DynamicPropertySource
  static void crypto(DynamicPropertyRegistry r) {
    r.add("mw.audit-dw.anchor.crypto.base-url", CRYPTO::baseUrl);
    r.add("mw.audit-dw.anchor.crypto.token-uri", CRYPTO::tokenUri);
    r.add("mw.audit-dw.anchor.export-dir", EXPORT::toString);
  }

  @AfterAll
  static void stop() {
    CRYPTO.close();
  }

  private final AnchorService anchors;
  private final AuditService audit;
  private final ChainVerifyService chains;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final TransactionTemplate tx;
  private final MeterRegistry meters;
  private final String url;

  @Autowired
  AuditAnchorIT(
      AnchorService anchors,
      AuditService audit,
      ChainVerifyService chains,
      Clock clock,
      DataSource dataSource,
      TransactionTemplate tx,
      MeterRegistry meters) {
    this.anchors = anchors;
    this.audit = audit;
    this.chains = chains;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    this.tx = tx;
    this.meters = meters;
    this.url = ((HikariDataSource) dataSource).getJdbcUrl();
    admin.execute("alter role audit_reader login password 'test-only-reader-pw'");
    admin.execute("alter role audit_writer login password '" + FAKE_WRITER_PASSWORD + "'");
  }

  @BeforeEach
  void clean() throws IOException {
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head, audit.anchor");
    CRYPTO.signRequests.clear();
    CRYPTO.signStatus(200);
    Files.createDirectories(EXPORT);
    try (var files = Files.list(EXPORT)) {
      for (Path f : files.toList()) {
        Files.delete(f);
      }
    }
    // two events on 3 Nov, one just before midnight on 4 Nov; the job runs on 5 Nov
    record(NOV_3, "RECEIVED", "VALIDATING");
    record(NOV_3.plusSeconds(1), "VALIDATING", "POSTING");
    record(NOV_4, "POSTING", "COMPLETED");
    clock.set(NOV_5);
  }

  private void record(Instant at, String from, String to) {
    clock.set(at);
    audit.record(AuditTrailIT.event(UUID.randomUUID(), from, to));
  }

  @Test
  void signsOneAnchorPerDayOverThatDaysChainHeadsChainedAndExported() throws Exception {
    List<String> made = anchors.anchorDue();

    // the chains start on 1 Nov: days without a write get an anchor with no heads
    assertThat(made).containsExactly("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04");
    assertThat(CRYPTO.signRequests).hasSize(4).allSatisfy(r -> assertThat(r).containsEntry("purpose", "AUDIT_ANCHOR_SIGN"));

    List<Map<String, Object>> rows =
        admin.queryForList("select anchor_date::text d, jws, kid from audit.anchor order by anchor_date");
    assertThat(rows).extracting(r -> r.get("kid")).containsOnly(CryptoStub.KID);
    Map<String, Object> nov3 = payload((String) rows.get(2).get("jws"));
    Map<String, Object> nov4 = payload((String) rows.get(3).get("jws"));

    assertThat(payload((String) rows.get(0).get("jws")))
        .containsEntry("date", "2026-11-01")
        .containsEntry("heads", List.of())
        .containsEntry("prevAnchorHash", ZEROS);
    assertThat(nov3).containsEntry("date", "2026-11-03");
    assertThat(nov3.get("heads"))
        .isEqualTo(List.of(Map.of("partition", "2026-11", "hash", rowHash(2), "rowCount", 2L)));
    assertThat(nov4.get("heads"))
        .isEqualTo(List.of(Map.of("partition", "2026-11", "hash", rowHash(3), "rowCount", 3L)));
    assertThat(nov4).containsEntry("prevAnchorHash", sha256Hex((String) rows.get(2).get("jws")));

    assertThat(Files.readString(EXPORT.resolve("2026-11-04.jws"))).isEqualTo(rows.get(3).get("jws"));

    // nothing more is due the same day
    assertThat(anchors.anchorDue()).isEmpty();

    List<AnchorVerification> results = anchors.verifyAll();
    assertThat(results).hasSize(4).allSatisfy(v -> assertThat(v.intact()).as(v.toString()).isTrue());
    assertThat(brokenGauge()).isZero();
    AnchorStatus status = anchors.status();
    assertThat(status.status()).isEqualTo(AnchorStatus.INTACT);
    assertThat(status.lastAnchorDate()).isEqualTo("2026-11-04");
  }

  @Test
  void aChainRewrittenConsistentlyIsCaughtByTheAnchor() {
    anchors.anchorDue();

    // a superuser changes row 1 and re-seals the whole chain and its head: the chain itself verifies
    AuditChainVerifier asAdmin = new AuditChainVerifier(admin);
    List<AuditRow> rows = asAdmin.rows("2026-11");
    List<AuditRow> resealed = new ArrayList<>();
    byte[] prev = HashChain.GENESIS;
    for (AuditRow r : rows) {
      AuditRow changed = r.seq() == 1 ? withOutcome(r, "FAILURE") : r;
      AuditRow sealed = HashChain.seal(changed, prev);
      resealed.add(sealed);
      prev = sealed.hash();
    }
    tx.executeWithoutResult(
        s -> {
          admin.execute("set local session_replication_role = replica");
          for (AuditRow r : resealed) {
            admin.update(
                "update audit.event set outcome = ?, prev_hash = ?, hash = ? where month = ? and seq = ?",
                r.outcome(), r.prevHash(), r.hash(), r.month(), r.seq());
          }
          admin.update("update audit.chain_head set hash = ? where month = '2026-11'", resealed.getLast().hash());
        });
    assertThat(chains.verifyAll().get(0).intact()).isTrue();

    List<AnchorVerification> results = anchors.verifyAll();

    assertThat(broken(results)).containsExactly("2026-11-03: chain heads differ", "2026-11-04: chain heads differ");
    assertThat(brokenGauge()).isEqualTo(2.0);
    assertThat(anchors.status().status()).isEqualTo(AnchorStatus.BROKEN);
  }

  @Test
  void aForgedOrMissingAnchorIsDetected() throws Exception {
    anchors.anchorDue();
    String nov3 = admin.queryForObject("select jws from audit.anchor where anchor_date = '2026-11-03'", String.class);
    String forged = CryptoStub.signWithForeignKey(JWSObject.parse(nov3).getPayload().toString());
    tx.executeWithoutResult(
        s -> {
          admin.execute("set local session_replication_role = replica");
          admin.update(
              "update audit.anchor set jws = ?, anchor_hash = ? where anchor_date = '2026-11-03'",
              forged, sha256(forged));
          admin.update("delete from audit.anchor where anchor_date = '2026-11-01'");
        });

    List<String> broken = broken(anchors.verifyAll());

    assertThat(broken)
        .containsExactly(
            "2026-11-01: anchor missing, export exists",
            "2026-11-02: anchor chain broken",
            "2026-11-03: signature does not verify",
            "2026-11-04: anchor chain broken");
  }

  /** After the grace (3 h by default) the previous day's anchor is due: 06:00 on 5 Nov. */
  private static final Instant NOV_5_LATE = Instant.parse("2026-11-05T06:00:00Z");

  private void superuser(String... statements) {
    tx.executeWithoutResult(
        s -> {
          admin.execute("set local session_replication_role = replica");
          for (String sql : statements) {
            admin.update(sql);
          }
        });
  }

  @Test
  void deletingTheNewestAnchorIsCaughtAndTheDayIsNeverReSigned() {
    anchors.anchorDue();
    superuser("delete from audit.anchor where anchor_date = '2026-11-04'");
    clock.set(NOV_5_LATE);

    assertThat(broken(anchors.verifyAll())).containsExactly("2026-11-04: anchor missing, export exists");
    assertThat(anchors.status().status()).isEqualTo(AnchorStatus.BROKEN);

    // the exported file is the outside copy: the day is not signed again over it
    assertThat(anchors.anchorDue()).isEmpty();
    assertThat(CRYPTO.signRequests).hasSize(4);
    assertThat(admin.queryForObject("select count(*) from audit.anchor", Long.class)).isEqualTo(3);
  }

  @Test
  void deletingEveryAnchorIsCaughtEvenWithoutTheExport() throws IOException {
    anchors.anchorDue();
    superuser("delete from audit.anchor");
    clock.set(NOV_5_LATE);

    assertThat(broken(anchors.verifyAll()))
        .containsExactly(
            "2026-11-01: anchor missing, export exists",
            "2026-11-02: anchor missing, export exists",
            "2026-11-03: anchor missing, export exists",
            "2026-11-04: anchor missing, export exists");
    assertThat(anchors.anchorDue()).isEmpty();

    // with the export gone too, the chain months still say anchors were due
    try (var files = Files.list(EXPORT)) {
      for (Path f : files.toList()) {
        Files.delete(f);
      }
    }
    assertThat(broken(anchors.verifyAll())).hasSize(4).allSatisfy(b -> assertThat(b).endsWith(": anchor missing"));
    assertThat(anchors.status().status()).isEqualTo(AnchorStatus.BROKEN);
  }

  @Test
  void anAnchorReSignedWithTheRealKeyButDifferentFromItsExportIsCaught() throws Exception {
    anchors.anchorDue();
    String nov4 = admin.queryForObject("select jws from audit.anchor where anchor_date = '2026-11-04'", String.class);
    // same claims, the real key, a fresh signature: signature, chain and heads all check out
    String resigned = CRYPTO.signWithStubKey(JWSObject.parse(nov4).getPayload().toString());
    assertThat(resigned).isNotEqualTo(nov4);
    tx.executeWithoutResult(
        s -> {
          admin.execute("set local session_replication_role = replica");
          admin.update(
              "update audit.anchor set jws = ?, anchor_hash = ? where anchor_date = '2026-11-04'",
              resigned, sha256(resigned));
        });

    assertThat(broken(anchors.verifyAll())).containsExactly("2026-11-04: export differs");

    Files.delete(EXPORT.resolve("2026-11-03.jws"));
    assertThat(broken(anchors.verifyAll())).containsExactly("2026-11-03: export missing", "2026-11-04: export differs");
  }

  @Test
  void anUnavailableExportFolderLeavesTheAnchorsUnverified() throws IOException {
    anchors.anchorDue();
    try (var files = Files.list(EXPORT)) {
      for (Path f : files.toList()) {
        Files.delete(f);
      }
    }
    Files.delete(EXPORT);

    assertThat(broken(anchors.verifyAll())).isEmpty();
    assertThat(anchors.status().status()).isEqualTo(AnchorStatus.UNVERIFIED);
  }

  @Test
  void noAnchorIsExpectedWithinTheGrace() {
    // 5 Nov 00:15: 4 Nov is not due before 03:00, so its absence is not a failure yet
    clock.set(Instant.parse("2026-11-04T12:00:00Z"));
    anchors.anchorDue();
    clock.set(NOV_5);

    assertThat(broken(anchors.verifyAll())).isEmpty();
    assertThat(anchors.status().status()).isEqualTo(AnchorStatus.INTACT);
  }

  @Test
  void theWriterCannotChangeAnAnchorAndMwCryptoDownLeavesTheDayDue() {
    anchors.anchorDue();
    JdbcTemplate writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=audit", "audit_writer", FAKE_WRITER_PASSWORD));
    assertThatThrownBy(() -> writer.update("update anchor set kid = 'x'")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> writer.update("delete from anchor")).isInstanceOf(DataAccessException.class);

    clock.set(NOV_5.plusSeconds(86_400));
    CRYPTO.signStatus(503);
    assertThatThrownBy(anchors::anchorDue).isInstanceOf(RuntimeException.class);
    assertThat(admin.queryForObject("select count(*) from audit.anchor", Long.class)).isEqualTo(4);

    CRYPTO.signStatus(200);
    assertThat(anchors.anchorDue()).containsExactly("2026-11-05");
  }

  private List<String> broken(List<AnchorVerification> results) {
    return results.stream().filter(v -> !v.intact()).map(v -> v.date() + ": " + v.reason()).toList();
  }

  private double brokenGauge() {
    return meters.get("mw.audit.anchor.broken").gauge().value();
  }

  private Map<String, Object> payload(String jws) throws ParseException {
    return JWSObject.parse(jws).getPayload().toJSONObject();
  }

  private String rowHash(long seq) {
    return HexFormat.of()
        .formatHex(admin.queryForObject("select hash from audit.event where month = '2026-11' and seq = ?", byte[].class, seq));
  }

  private static AuditRow withOutcome(AuditRow r, String outcome) {
    return new AuditRow(
        r.month(), r.seq(), r.auditId(), r.recordedAt(), r.at(), r.txnId(), r.correlationId(), r.actorType(),
        r.actorId(), r.action(), r.resource(), outcome, r.service(), r.sourceIp(), r.payloadRef(), r.before(),
        r.after(), null, null);
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256Hex(String s) {
    return HexFormat.of().formatHex(sha256(s));
  }

  private static Path tempDir() {
    try {
      return Files.createTempDirectory("audit-anchors");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
