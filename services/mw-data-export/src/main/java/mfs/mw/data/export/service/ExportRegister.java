package mfs.mw.data.export.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import mfs.mw.data.export.messaging.EventMetadata;
import mfs.mw.data.export.messaging.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The day register and the operator audit of a forced re-export, in one transaction. */
@Service
public class ExportRegister {

  private final JdbcTemplate jdbc;
  private final OutboxWriter outbox;
  private final DwEvents events;
  private final Clock clock;

  public ExportRegister(JdbcTemplate jdbc, OutboxWriter outbox, DwEvents events, Clock clock) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.events = events;
    this.clock = clock;
  }

  public boolean exported(LocalDate date) {
    Integer n = jdbc.queryForObject("select count(*) from export_day where export_date = ?", Integer.class, date);
    return n != null && n > 0;
  }

  @Transactional
  public void mark(LocalDate date, String operatorId, boolean force) {
    jdbc.update(
        """
        insert into export_day (export_date, exported_at, operator_id) values (?, ?, ?)
        on conflict (export_date) do update set exported_at = excluded.exported_at, operator_id = excluded.operator_id
        """,
        date, OffsetDateTime.now(clock), operatorId);
    if (!force) {
      return;
    }
    if (operatorId == null || operatorId.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "operator id is required");
    }
    String auditId = DwEvents.newId();
    String correlationId = DwEvents.newId();
    Map<String, Object> payload = events.replayAudit(operatorId, date, clock.instant(), auditId, correlationId);
    outbox.append(DwTopics.AUDIT_EVENT, operatorId,
        new EventMetadata(correlationId, auditId, "dw-export-" + date, 1), payload);
  }
}
