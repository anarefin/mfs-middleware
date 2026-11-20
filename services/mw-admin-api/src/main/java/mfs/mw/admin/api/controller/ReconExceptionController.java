package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ReconExceptionChangeBody;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.ReconExceptionActionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An action on a reconciliation exception (ticket 127b, UC-22; contract {@code api/mw-admin-api/admin.v1}): a
 * {@code recon_exception} change request; approve and reject are the shared change-request endpoints.
 */
@RestController
@RequestMapping("/admin/v1/recon-exceptions")
public class ReconExceptionController {

  private final ReconExceptionActionService service;
  private final OperatorResolver operators;

  public ReconExceptionController(ReconExceptionActionService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @PostMapping("/{exceptionId}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView request(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID exceptionId, @Valid @RequestBody ReconExceptionChangeBody body) {
    return service.open(operators.resolve(jwt), exceptionId, body);
  }
}
