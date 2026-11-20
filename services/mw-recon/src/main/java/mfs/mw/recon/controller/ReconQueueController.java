package mfs.mw.recon.controller;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.dto.ExplanationRequest;
import mfs.mw.recon.dto.ReportFormat;
import mfs.mw.recon.dto.ReportKind;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.service.ExceptionQueueService;
import mfs.mw.recon.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports and the exception work queue (ticket 127, FRS REC-05, REC-06, UC-22). Internal only: mw-admin-api calls
 * it for the console workbench. Nothing here moves money.
 */
@RestController
public class ReconQueueController {

  private final ReportService reports;
  private final ExceptionQueueService queue;

  public ReconQueueController(ReportService reports, ExceptionQueueService queue) {
    this.reports = reports;
    this.queue = queue;
  }

  @GetMapping("/internal/v1/recon/reports")
  public ResponseEntity<byte[]> report(
      @RequestParam String partner,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(name = "kind", defaultValue = "DAILY") String kindText,
      @RequestParam(name = "format", defaultValue = "csv") String formatText) {
    ReportKind kind = ReportKind.of(kindText);
    ReportFormat format = ReportFormat.of(formatText);
    if (to.isBefore(from)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "to is before from");
    }
    byte[] body = reports.download(partner, from, to, kind, format);
    String filename = "recon-%s-%s-%s-%s.%s".formatted(partner.replaceAll("[^A-Za-z0-9_-]", "_"),
        kind.name().toLowerCase(java.util.Locale.ROOT), from, to, format.extension());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(format.mediaType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .body(body);
  }

  @GetMapping("/internal/v1/recon/exceptions")
  public List<ExceptionView> exceptions(
      @RequestParam(name = "class", required = false) String reconClass,
      @RequestParam(required = false) String partner,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) Integer minAgeHours,
      @RequestParam(defaultValue = "100") int limit) {
    return queue.list(reconClass, partner, state, minAgeHours, limit);
  }

  @GetMapping("/internal/v1/recon/exceptions/{id}")
  public ExceptionView exception(@PathVariable UUID id) {
    return queue.get(id);
  }

  @PostMapping("/internal/v1/recon/exceptions/{id}/explanations")
  public ExceptionView explain(@PathVariable UUID id, @RequestHeader("X-Mw-Operator-Id") String operatorId,
      @Valid @RequestBody ExplanationRequest request) {
    return queue.explain(id, request.explanation(), operatorId);
  }
}
