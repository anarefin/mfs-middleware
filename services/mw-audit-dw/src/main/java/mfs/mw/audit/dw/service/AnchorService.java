package mfs.mw.audit.dw.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.audit.dw.client.CryptoClient;
import mfs.mw.audit.dw.config.AnchorProperties;
import mfs.mw.audit.dw.dto.AnchorStatus;
import mfs.mw.audit.dw.dto.AuditAnchor;
import mfs.mw.audit.dw.entity.AnchorRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed daily audit anchor (spec 14.3, decision 2026-11-14, ticket 74).
 *
 * <p><b>Shape.</b> The chains are monthly, so a day's anchor covers the head of every monthly chain
 * written that UTC day: {@code {date, heads[{partition, hash, rowCount}], prevAnchorHash}}
 * ({@link AuditAnchor}); a head is the chain's last row recorded before midnight, {@code rowCount}
 * its seq. A day without writes gets an anchor with no heads, so the anchors are one per day without
 * gaps from the first day of the first chain's month. {@code prevAnchorHash} is the hex SHA-256 of
 * the previous anchor's compact JWS (64 zeros for the first): the anchors are a chain of their own.
 *
 * <p><b>Signing.</b> {@link #anchorDue()} signs every past day not yet anchored (at most {@code
 * catch-up-days} per run) through mw-crypto ({@code AUDIT_ANCHOR_SIGN}, ES256, {@code typ}
 * {@value CryptoClient#ANCHOR_TYPE}), stores it in the INSERT-only {@code audit.anchor} (as the
 * writer) and writes {@code <date>.jws} to the export folder (WORM target TBC). It stops at the first
 * failure; the day stays due for the next run. Heads are read as the read-only {@code audit_reader}.
 *
 * <p><b>Verification.</b> {@link #verifyAll()} checks every stored anchor: its stored hash, the ES256
 * signature against mw-crypto's {@code AUDIT_ANCHOR_SIGN} JWKS (by kid), its date, no missing day,
 * the anchor chain, and that its heads equal the heads recomputed from {@code audit.event} now. The
 * last check catches a chain a superuser rewrote and re-sealed consistently, which the hash chain
 * alone cannot. A failure is the ERROR event {@code audit.anchor.broken} (date, reason) and counts in
 * the gauge {@code mw.audit.anchor.broken}; a JWKS that cannot be read is WARN {@code
 * audit.anchor.unverifiable} and leaves the status {@code UNVERIFIED}.
 */
@Service
@ConditionalOnProperty(
    name = {"mw.audit-dw.anchor.enabled", "mw.audit-dw.chain-verify.enabled"},
    havingValue = "true",
    matchIfMissing = true)
public class AnchorService {

  static final String GENESIS = "0".repeat(64);
  private static final Logger log = LoggerFactory.getLogger(AnchorService.class);
  private static final HexFormat HEX = HexFormat.of();

  private final JdbcTemplate jdbc;
  private final AuditChainVerifier reader;
  private final CryptoClient crypto;
  private final AnchorProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final AtomicInteger broken = new AtomicInteger();
  private volatile AnchorStatus status = new AnchorStatus(AnchorStatus.UNVERIFIED, null, null);

  public AnchorService(
      JdbcTemplate jdbc,
      AuditChainVerifier reader,
      CryptoClient crypto,
      AnchorProperties props,
      JsonMapper json,
      Clock clock,
      MeterRegistry meters) {
    this.jdbc = jdbc;
    this.reader = reader;
    this.crypto = crypto;
    this.props = props;
    this.json = json;
    this.clock = clock;
    Gauge.builder("mw.audit.anchor.broken", broken, AtomicInteger::get)
        .description("Daily audit anchors that failed the last verification")
        .register(meters);
  }

  /** The status of the last verification, for the audit search. */
  public AnchorStatus status() {
    return status;
  }

  /**
   * Signs and stores the anchor of every past UTC day not yet anchored, oldest first.
   *
   * @return the dates anchored ({@code yyyy-MM-dd})
   * @throws RuntimeException when mw-crypto or the database fails; earlier days stay stored
   */
  public List<String> anchorDue() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    List<AnchorRow> last =
        jdbc.query(
            "select anchor_date, jws, kid, anchor_hash from anchor order by anchor_date desc limit 1",
            (rs, i) ->
                new AnchorRow(
                    rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), rs.getBytes(4)));
    LocalDate next;
    String prev;
    if (last.isEmpty()) {
      List<String> months = reader.months();
      if (months.isEmpty()) {
        return List.of();
      }
      next = YearMonth.parse(months.get(0)).atDay(1);
      prev = GENESIS;
    } else {
      next = last.get(0).date().plusDays(1);
      prev = HEX.formatHex(last.get(0).anchorHash());
    }
    List<String> made = new ArrayList<>();
    for (int i = 0; i < props.catchUpDays() && next.isBefore(today); i++, next = next.plusDays(1)) {
      Instant from = next.atStartOfDay(ZoneOffset.UTC).toInstant();
      AuditAnchor anchor = new AuditAnchor(next.toString(), reader.heads(from, from.plusSeconds(86_400)), prev);
      CryptoClient.Signed signed = crypto.sign(anchor);
      byte[] hash = sha256(signed.jws());
      jdbc.update(
          """
          insert into anchor (anchor_date, payload, jws, kid, anchor_hash, prev_anchor_hash, created_at)
          values (?, ?::jsonb, ?, ?, ?, ?, ?)
          """,
          next,
          json.writeValueAsString(anchor),
          signed.jws(),
          signed.kid(),
          hash,
          HEX.parseHex(prev),
          OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
      log.atInfo()
          .addKeyValue("event", "audit.anchor.signed")
          .addKeyValue("date", anchor.date())
          .addKeyValue("kid", signed.kid())
          .addKeyValue("heads", anchor.heads().size())
          .log("daily audit anchor signed and stored");
      export(anchor.date(), signed.jws());
      prev = HEX.formatHex(hash);
      made.add(anchor.date());
      status = new AnchorStatus(status.status(), anchor.date(), status.verifiedAt());
    }
    return made;
  }

  /** Verifies every stored anchor (see the class doc); one result per anchor, oldest first. */
  public List<AnchorVerification> verifyAll() {
    List<AnchorRow> anchors = reader.anchors();
    Instant now = clock.instant();
    String lastDate = anchors.isEmpty() ? null : anchors.getLast().date().toString();
    JWKSet keys = null;
    if (!anchors.isEmpty()) {
      try {
        keys = crypto.jwks();
      } catch (RuntimeException e) {
        log.atWarn()
            .addKeyValue("event", "audit.anchor.unverifiable")
            .addKeyValue("errorClass", e.getClass().getSimpleName())
            .log("audit anchor signing keys could not be read; anchors not verified");
        status = new AnchorStatus(AnchorStatus.UNVERIFIED, lastDate, status.verifiedAt());
        return List.of();
      }
    }
    List<AnchorVerification> results = new ArrayList<>();
    String prev = GENESIS;
    LocalDate prevDate = null;
    for (AnchorRow a : anchors) {
      String reason;
      try {
        reason = check(a, prev, prevDate, keys);
      } catch (RuntimeException e) {
        reason = "unreadable: " + e.getClass().getSimpleName();
      }
      AnchorVerification v =
          reason == null ? AnchorVerification.ok(a.date().toString()) : AnchorVerification.broken(a.date().toString(), reason);
      results.add(v);
      if (!v.intact()) {
        log.atError()
            .addKeyValue("event", "audit.anchor.broken")
            .addKeyValue("date", v.date())
            .addKeyValue("reason", v.reason())
            .log("audit anchor verification failed");
      }
      prev = HEX.formatHex(sha256(a.jws()));
      prevDate = a.date();
    }
    int count = (int) results.stream().filter(v -> !v.intact()).count();
    broken.set(count);
    status = new AnchorStatus(count == 0 ? AnchorStatus.INTACT : AnchorStatus.BROKEN, lastDate, now);
    log.atInfo()
        .addKeyValue("event", "audit.anchor.verified")
        .addKeyValue("anchors", results.size())
        .addKeyValue("broken", count)
        .log("audit anchors verified");
    return results;
  }

  /** @return null when intact, else the first reason it fails */
  private String check(AnchorRow a, String prevHash, LocalDate prevDate, JWKSet keys) {
    if (!Arrays.equals(a.anchorHash(), sha256(a.jws()))) {
      return "stored hash does not match the JWS";
    }
    JWSObject jws;
    try {
      jws = JWSObject.parse(a.jws());
    } catch (ParseException e) {
      return "not a JWS";
    }
    if (!verifies(jws, keys)) {
      return "signature does not verify";
    }
    AuditAnchor claims;
    try {
      claims = json.readValue(jws.getPayload().toString(), AuditAnchor.class);
    } catch (JacksonException e) {
      return "payload unreadable";
    }
    if (!a.date().toString().equals(claims.date())) {
      return "date does not match";
    }
    if (prevDate != null && !a.date().equals(prevDate.plusDays(1))) {
      return "anchor missing before it";
    }
    if (!prevHash.equals(claims.prevAnchorHash())) {
      return "anchor chain broken";
    }
    Instant from = a.date().atStartOfDay(ZoneOffset.UTC).toInstant();
    if (!claims.heads().equals(reader.heads(from, from.plusSeconds(86_400)))) {
      return "chain heads differ";
    }
    return null;
  }

  private static boolean verifies(JWSObject jws, JWKSet keys) {
    if (!JWSAlgorithm.ES256.equals(jws.getHeader().getAlgorithm())
        || !new JOSEObjectType(CryptoClient.ANCHOR_TYPE).equals(jws.getHeader().getType())
        || jws.getHeader().getKeyID() == null) {
      return false;
    }
    JWK key = keys.getKeyByKeyId(jws.getHeader().getKeyID());
    if (!(key instanceof ECKey ec)) {
      return false;
    }
    try {
      return jws.verify(new ECDSAVerifier(ec.toPublicJWK()));
    } catch (JOSEException e) {
      return false;
    }
  }

  private void export(String date, String jws) {
    String dir = props.exportDir();
    if (dir == null || dir.isBlank()) {
      return;
    }
    try {
      Path folder = Files.createDirectories(Path.of(dir));
      Path target = folder.resolve(date + ".jws");
      if (Files.exists(target)) {
        return;
      }
      Path tmp = folder.resolve("." + date + ".jws.tmp");
      Files.writeString(tmp, jws, StandardCharsets.US_ASCII);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      log.atError()
          .addKeyValue("event", "audit.anchor.export_failed")
          .addKeyValue("date", date)
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("audit anchor stored but not exported");
    }
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
