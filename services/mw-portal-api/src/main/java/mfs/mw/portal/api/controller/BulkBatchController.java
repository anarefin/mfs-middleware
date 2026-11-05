package mfs.mw.portal.api.controller;

import jakarta.validation.Valid;
import java.net.URI;
import mfs.mw.portal.api.dto.BatchList;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.RejectRequest;
import mfs.mw.portal.api.security.PortalUserResolver;
import mfs.mw.portal.api.service.BatchService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Org portal bulk disbursement API v1 (spec 9.4; contract {@code
 * api/mw-portal-api/portal-org.v1.openapi.yaml}).
 */
@RestController
@RequestMapping("/portal/org/v1/bulk/batches")
public class BulkBatchController {

  static final String CSV = "text/csv";

  private final BatchService service;
  private final PortalUserResolver users;

  public BulkBatchController(BatchService service, PortalUserResolver users) {
    this.service = service;
    this.users = users;
  }

  /** 201 with the stored batch (PENDING_APPROVAL); 400 MW-VAL-030/031 when the file is refused. */
  @PostMapping(consumes = CSV)
  public ResponseEntity<BatchView> upload(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] file) {
    BatchView view = service.upload(users.resolve(jwt), file);
    return ResponseEntity.created(URI.create("/portal/org/v1/bulk/batches/" + view.batchId())).body(view);
  }

  /** The org's batches; {@code status} is a contract batch status (400 MW-VAL-001 otherwise). */
  @GetMapping
  public BatchList list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
    return new BatchList(service.list(users.resolve(jwt), status));
  }

  @GetMapping("/{batchId}")
  public BatchView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String batchId) {
    return service.get(users.resolve(jwt), batchId);
  }

  @PostMapping("/{batchId}/approve")
  public BatchView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String batchId) {
    return service.approve(users.resolve(jwt), batchId);
  }

  /** Stops sending further rows (checker); 409 MW-VAL-033 when the batch is not running. */
  @PostMapping("/{batchId}/pause")
  public BatchView pause(@AuthenticationPrincipal Jwt jwt, @PathVariable String batchId) {
    return service.pause(users.resolve(jwt), batchId);
  }

  /** Continues a paused batch (checker); 409 MW-VAL-033 when it is not paused. */
  @PostMapping("/{batchId}/resume")
  public BatchView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String batchId) {
    return service.resume(users.resolve(jwt), batchId);
  }

  /** The result file (CSV) of an approved batch; 409 MW-VAL-035 before approval. */
  @GetMapping("/{batchId}/result")
  public ResponseEntity<String> result(@AuthenticationPrincipal Jwt jwt, @PathVariable String batchId) {
    String csv = service.resultFile(users.resolve(jwt), batchId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(CSV))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bulk-" + batchId + "-result.csv\"")
        .body(csv);
  }

  @PostMapping("/{batchId}/reject")
  public BatchView reject(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String batchId, @Valid @RequestBody RejectRequest body) {
    return service.reject(users.resolve(jwt), batchId, body.reason());
  }
}
