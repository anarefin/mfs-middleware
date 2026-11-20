package mfs.mw.data.export.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import mfs.mw.data.export.config.ArchiveProperties;
import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Moves batch facts older than the online window into gzipped CSV files and deletes the rows
 * (ticket 128). The audit event and payload tables are not this service's. An archive older than
 * its keep window is removed. {@code GET /internal/v1/archive/{type}/{date}} returns the CSV.
 */
@Service
public class ArchivalJob {

  public static final String LOCK = "dw-archival";

  private final ArchiveProperties properties;
  private final DwFactStore facts;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ZoneId zone;

  public ArchivalJob(ArchiveProperties properties, DwFactStore facts, JdbcTemplate jdbc, Clock clock) {
    this.properties = properties;
    this.facts = facts;
    this.jdbc = jdbc;
    this.clock = clock;
    this.zone = ZoneId.of(properties.zone());
  }

  @Scheduled(cron = "${mw.archive.cron}", zone = "${mw.archive.zone}")
  @SchedulerLock(name = LOCK, lockAtMostFor = "PT30M")
  public void scheduled() {
    archive();
  }

  /** Archives every stored day older than its online window, then drops archives past keep. */
  public int archive() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
    int moved = 0;
    LocalDate txnCutoff = today.minusDays(properties.online().transactions().toDays());
    LocalDate notifyCutoff = today.minusDays(properties.online().notifications().toDays());
    LocalDate reconCutoff = today.minusDays(properties.online().recon().toDays());
    moved += move("transactions", txnCutoff, facts.transactionDaysBefore(txnCutoff));
    moved += move("notifications", notifyCutoff, facts.notificationDaysBefore(notifyCutoff));
    moved += move("recon", reconCutoff, facts.reconDaysBefore(reconCutoff));
    purgeKept("transactions", properties.keep().transactions());
    purgeKept("notifications", properties.keep().notifications());
    purgeKept("recon", properties.keep().recon());
    return moved;
  }

  /** The CSV of an archived day, or {@code MW-VAL-003} when that day was not archived. */
  public byte[] read(String type, LocalDate date) {
    if (!List.of("transactions", "notifications", "recon").contains(type)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "unknown archive type");
    }
    List<String> paths = jdbc.query(
        "select path from archive_index where data_type = ? and archive_date = ?",
        (rs, n) -> rs.getString(1), type, date);
    if (paths.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no archive for " + type + " " + date);
    }
    try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(Path.of(paths.get(0))))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "archive unreadable", e);
    }
  }

  private int move(String type, LocalDate cutoff, List<LocalDate> days) {
    int n = 0;
    for (LocalDate day : days) {
      if (!day.isBefore(cutoff)) {
        continue;
      }
      byte[] csv = csv(type, day);
      Path path = Path.of(properties.directory(), type, day + ".csv.gz");
      try {
        Files.createDirectories(path.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
          out.write(csv);
        }
      } catch (IOException e) {
        throw new MwException(ErrorCodes.SYS_UPSTREAM, "archive write failed", e);
      }
      jdbc.update(
          """
          insert into archive_index (data_type, archive_date, path, sha256, row_count, archived_at)
          values (?, ?, ?, ?, ?, ?)
          on conflict (data_type, archive_date) do update set
            path = excluded.path, sha256 = excluded.sha256, row_count = excluded.row_count,
            archived_at = excluded.archived_at
          """,
          type, day, path.toString(), DwDailyExportJob.sha256(csv), rows(csv), OffsetDateTime.now(clock));
      switch (type) {
        case "transactions" -> facts.deleteTransactions(day);
        case "notifications" -> facts.deleteNotifications(day);
        case "recon" -> facts.deleteRecon(day);
        default -> throw new MwException(ErrorCodes.VAL_NOT_FOUND, "unknown archive type");
      }
      n++;
    }
    return n;
  }

  private void purgeKept(String type, java.time.Duration keep) {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(keep);
    List<Map<String, Object>> old = jdbc.queryForList(
        "select path, archive_date from archive_index where data_type = ? and archived_at < ?", type, cutoff);
    for (Map<String, Object> row : old) {
      try {
        Files.deleteIfExists(Path.of(String.valueOf(row.get("path"))));
      } catch (IOException e) {
        throw new MwException(ErrorCodes.SYS_UPSTREAM, "archive delete failed", e);
      }
      jdbc.update("delete from archive_index where data_type = ? and archive_date = ?", type, row.get("archive_date"));
    }
  }

  private byte[] csv(String type, LocalDate day) {
    StringBuilder sb = new StringBuilder();
    switch (type) {
      case "transactions" -> {
        sb.append("txn_id,type,status,amount_minor,currency,channel,payer_token,payee_token,created_at,completed_at\n");
        for (Map<String, Object> row : facts.transactions(day)) {
          sb.append(String.join(",",
              text(row.get("txn_id")), text(row.get("txn_type")), text(row.get("status")),
              text(row.get("amount_minor")), text(row.get("currency")), text(row.get("channel")),
              text(row.get("payer_token")), text(row.get("payee_token")),
              text(row.get("created_at")), text(row.get("completed_at")))).append('\n');
        }
      }
      case "notifications" -> {
        sb.append("notification_id,channel,template,status,recipient_token,sent_at\n");
        for (Map<String, Object> row : facts.notifications(day)) {
          sb.append(String.join(",",
              text(row.get("notification_id")), text(row.get("channel")), text(row.get("template")),
              text(row.get("status")), text(row.get("recipient_token")), text(row.get("sent_at")))).append('\n');
        }
      }
      case "recon" -> {
        sb.append("exception_id,class,partner,txn_id,amount_minor,statement_ref,detected_at\n");
        for (Map<String, Object> row : facts.recon(day)) {
          sb.append(String.join(",",
              text(row.get("exception_id")), text(row.get("exception_class")), text(row.get("partner")),
              text(row.get("txn_id")), text(row.get("amount_minor")), text(row.get("statement_ref")),
              text(row.get("detected_at")))).append('\n');
        }
      }
      default -> throw new MwException(ErrorCodes.VAL_NOT_FOUND, "unknown archive type");
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static long rows(byte[] csv) {
    String text = new String(csv, StandardCharsets.UTF_8);
    long lines = text.lines().filter(l -> !l.isBlank()).count();
    return Math.max(0, lines - 1);
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
