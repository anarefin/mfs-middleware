package mfs.mw.data.export.controller;

import java.time.LocalDate;
import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import mfs.mw.data.export.service.ArchivalJob;
import mfs.mw.data.export.service.DwDailyExportJob;
import mfs.mw.data.export.service.DwDailyExportJob.ExportResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator calls that are not on the edge (ticket 128): re-export one day, and read an archived
 * day back. {@code /internal/*} is never routed by APISIX.
 */
@RestController
public class DwExportController {

  private final DwDailyExportJob exports;
  private final ArchivalJob archival;

  public DwExportController(DwDailyExportJob exports, ArchivalJob archival) {
    this.exports = exports;
    this.archival = archival;
  }

  /** Forces the drop for {@code date} and audits the operator. */
  @PostMapping("/internal/v1/exports/{date}")
  public ExportResult reexport(@PathVariable LocalDate date, @RequestHeader("X-Mw-Operator-Id") String operatorId) {
    if (operatorId == null || operatorId.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "operator id is required");
    }
    return exports.export(date, operatorId, true);
  }

  /** The CSV of an archived day. */
  @GetMapping(value = "/internal/v1/archive/{type}/{date}", produces = "text/csv")
  public ResponseEntity<byte[]> archive(@PathVariable String type, @PathVariable LocalDate date) {
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(archival.read(type, date));
  }
}
