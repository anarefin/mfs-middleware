package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.crypto.dto.CeremonyCommitRequest;
import mfs.mw.crypto.dto.CeremonyComponentRequest;
import mfs.mw.crypto.dto.CeremonyOpenRequest;
import mfs.mw.crypto.dto.KeyChangeState;
import mfs.mw.crypto.dto.CeremonyView;
import mfs.mw.crypto.dto.KeyChangeRequest;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.service.CeremonyService;
import mfs.mw.crypto.service.KeyChangeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dual-control key management (ticket 75; crypto-internal.v1): approved key changes and the key
 * ceremony. Key-admin clients only ({@code config/SecurityConfig}: mw-admin-api).
 */
@RestController
@RequestMapping("/internal/v1")
public class CeremonyController {

  private final CeremonyService ceremonies;
  private final KeyChangeService changes;

  public CeremonyController(CeremonyService ceremonies, KeyChangeService changes) {
    this.ceremonies = ceremonies;
    this.changes = changes;
  }

  @PutMapping("/key-changes/{changeRequestId}")
  public KeyView keyChange(@PathVariable UUID changeRequestId, @Valid @RequestBody KeyChangeRequest request) {
    return changes.apply(changeRequestId, request);
  }

  @GetMapping("/key-changes/{changeRequestId}")
  public KeyChangeState keyChangeState(@PathVariable UUID changeRequestId) {
    return changes.state(changeRequestId);
  }

  /** Makes "not applied" binding for attempt {@code changeRequestId} (terminal unless applied). */
  @PostMapping("/key-changes/{changeRequestId}/cancel")
  public KeyChangeState cancelKeyChange(@PathVariable UUID changeRequestId) {
    return changes.cancel(changeRequestId);
  }

  @GetMapping("/ceremonies")
  public mfs.mw.crypto.dto.LiveCeremonies liveCeremonies(
      @org.springframework.web.bind.annotation.RequestParam
          @jakarta.validation.constraints.Pattern(regexp = "LIVE") String state) {
    return ceremonies.live();
  }

  @PutMapping("/ceremonies/{ceremonyId}")
  public CeremonyView open(@PathVariable UUID ceremonyId, @Valid @RequestBody CeremonyOpenRequest request) {
    return ceremonies.open(ceremonyId, request.keyId(), request.purpose());
  }

  @GetMapping("/ceremonies/{ceremonyId}")
  public CeremonyView get(@PathVariable UUID ceremonyId) {
    return ceremonies.get(ceremonyId);
  }

  @PostMapping("/ceremonies/{ceremonyId}/components")
  public CeremonyView component(@PathVariable UUID ceremonyId, @Valid @RequestBody CeremonyComponentRequest request) {
    return ceremonies.component(ceremonyId, request);
  }

  @PostMapping("/ceremonies/{ceremonyId}/commit")
  public KeyView commit(@PathVariable UUID ceremonyId, @Valid @RequestBody CeremonyCommitRequest request) {
    return ceremonies.commit(ceremonyId, request.makerId(), request.checkerId());
  }

  @PostMapping("/ceremonies/{ceremonyId}/abort")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void abort(@PathVariable UUID ceremonyId) {
    ceremonies.abort(ceremonyId);
  }
}
