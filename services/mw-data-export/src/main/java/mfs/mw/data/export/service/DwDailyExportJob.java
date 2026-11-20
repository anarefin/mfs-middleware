package mfs.mw.data.export.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.data.export.client.SftpDrop;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The daily D-1 drop (ticket 128): four CSV files and {@code control_<date>.json}, uploaded to
 * {@code dw/in}. A day is uploaded once. The SFTP call sits outside the database transaction; the
 * register row, and the operator audit when the call is forced, commit after a successful upload.
 */
@Service
public class DwDailyExportJob {

  public static final String LOCK = "dw-daily-export";

  public record FileReport(String name, long rows, String sha256) {}

  public record ExportResult(String date, boolean uploaded, List<FileReport> files) {
    static ExportResult skipped(LocalDate date) {
      return new ExportResult(date.toString(), false, List.of());
    }
  }

  private final ExportPropertiesZone zone;
  private final DwFactStore facts;
  private final PiiPolicy pii;
  private final WalletSnapshotSource wallets;
  private final SftpDrop sftp;
  private final ExportRegister register;
  private final JsonMapper json;
  private final java.time.Clock clock;

  public DwDailyExportJob(ExportPropertiesZone zone, DwFactStore facts, PiiPolicy pii,
      WalletSnapshotSource wallets, SftpDrop sftp, ExportRegister register, JsonMapper json, java.time.Clock clock) {
    this.zone = zone;
    this.facts = facts;
    this.pii = pii;
    this.wallets = wallets;
    this.sftp = sftp;
    this.register = register;
    this.json = json;
    this.clock = clock;
  }

  /** Yesterday in Asia/Dhaka, just after midnight. */
  @Scheduled(cron = "${mw.export.cron}", zone = "${mw.export.zone}")
  @SchedulerLock(name = LOCK, lockAtMostFor = "PT30M")
  public void scheduled() {
    export(LocalDate.now(zone.zone()).minusDays(1), null, false);
  }

  /**
   * Builds and uploads {@code date}. A second non-force call uploads nothing. {@code force} is the
   * operator re-export: it overwrites the files and records {@code dw.export.replayed}.
   */
  public ExportResult export(LocalDate date, String operatorId, boolean force) {
    if (!force && register.exported(date)) {
      return ExportResult.skipped(date);
    }
    List<Named> files = filesFor(date);
    for (Named file : files) {
      sftp.upload(file.name(), file.body());
    }
    register.mark(date, operatorId, force);
    return new ExportResult(date.toString(), true, files.stream().map(Named::report).toList());
  }

  private List<Named> filesFor(LocalDate date) {
    Instant now = clock.instant();
    List<String> txnKeys = List.of("txn_id", "type", "status", "amount_minor", "currency", "channel",
        "payer_token", "payee_token", "created_at", "completed_at");
    Named transactions = csv("transactions_" + date + ".csv",
        String.join(",", txnKeys),
        facts.transactions(date).stream().map(row -> cells(DwTopics.FILE_TRANSACTIONS, txnKeys, ordered(
            "txn_id", text(row.get("txn_id")),
            "type", text(row.get("txn_type")),
            "status", text(row.get("status")),
            "amount_minor", text(row.get("amount_minor")),
            "currency", text(row.get("currency")),
            "channel", text(row.get("channel")),
            "payer_token", text(row.get("payer_token")),
            "payee_token", text(row.get("payee_token")),
            "created_at", text(row.get("created_at")),
            "completed_at", text(row.get("completed_at"))))).toList());
    List<String> walletKeys = List.of("wallet_token", "type", "status", "balance_minor", "held_minor", "snapshot_at");
    Named walletsFile = csv("wallet_snapshot_" + date + ".csv",
        String.join(",", walletKeys),
        wallets.lines(now).stream().map(line -> cells(DwTopics.FILE_WALLETS, walletKeys, ordered(
            "wallet_token", line.walletId(),
            "type", line.type(),
            "status", line.status(),
            "balance_minor", Long.toString(line.balanceMinor()),
            "held_minor", Long.toString(line.heldMinor()),
            "snapshot_at", now.toString()))).toList());
    List<String> notifyKeys = List.of("notification_id", "channel", "template", "status", "recipient_token", "sent_at");
    Named notifications = csv("notifications_" + date + ".csv",
        String.join(",", notifyKeys),
        facts.notifications(date).stream().map(row -> cells(DwTopics.FILE_NOTIFICATIONS, notifyKeys, ordered(
            "notification_id", text(row.get("notification_id")),
            "channel", text(row.get("channel")),
            "template", text(row.get("template")),
            "status", text(row.get("status")),
            "recipient_token", text(row.get("recipient_token")),
            "sent_at", text(row.get("sent_at"))))).toList());
    List<String> reconKeys = List.of("exception_id", "class", "partner", "txn_id", "amount_minor",
        "statement_ref", "detected_at");
    Named recon = csv("recon_" + date + ".csv",
        String.join(",", reconKeys),
        facts.recon(date).stream().map(row -> cells(DwTopics.FILE_RECON, reconKeys, ordered(
            "exception_id", text(row.get("exception_id")),
            "class", text(row.get("exception_class")),
            "partner", text(row.get("partner")),
            "txn_id", text(row.get("txn_id")),
            "amount_minor", text(row.get("amount_minor")),
            "statement_ref", text(row.get("statement_ref")),
            "detected_at", text(row.get("detected_at"))))).toList());
    List<Named> data = List.of(transactions, walletsFile, notifications, recon);
    Named control = control(date, data);
    List<Named> all = new ArrayList<>(data);
    all.add(control);
    return all;
  }

  private List<String> cells(String scope, List<String> keys, Map<String, String> fields) {
    Map<String, Object> applied = pii.apply(scope, new LinkedHashMap<>(fields));
    return keys.stream().map(key -> cell(applied.get(key))).toList();
  }

  private static Map<String, String> ordered(String... kv) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      out.put(kv[i], kv[i + 1]);
    }
    return out;
  }

  private Named csv(String name, String header, List<List<String>> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append(header).append('\n');
    for (List<String> row : rows) {
      sb.append(String.join(",", row)).append('\n');
    }
    byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
    return new Named(name, body, rows.size());
  }

  private Named control(LocalDate date, List<Named> files) {
    List<Map<String, Object>> listed = new ArrayList<>();
    for (Named file : files) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("name", file.name());
      one.put("rows", file.rows());
      one.put("sha256", sha256(file.body()));
      listed.add(one);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("date", date.toString());
    doc.put("files", listed);
    byte[] body = json.writeValueAsBytes(doc);
    return new Named("control_" + date + ".json", body, files.size());
  }

  static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256", e);
    }
  }

  private static String cell(Object value) {
    String text = value == null ? "" : String.valueOf(value);
    if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private record Named(String name, byte[] body, long rows) {
    FileReport report() {
      return new FileReport(name, rows, sha256(body));
    }
  }
}
