package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.CredentialChangeBody;
import mfs.mw.admin.api.dto.CredentialView;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.CredentialService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner credentials (ticket 131, FRS ADM-02; contract {@code admin.v1}): metadata only, the
 * expiry dashboard, and the write-only upload through maker-checker. Approve / reject go through
 * {@code ChangeRequestController}; roles are checked by the service.
 */
@RestController
public class CredentialController {

  private final CredentialService service;
  private final OperatorResolver operators;

  public CredentialController(CredentialService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @GetMapping("/admin/v1/credentials")
  public CredentialView.Page list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String partnerId) {
    return service.list(operators.resolve(jwt), partnerId);
  }

  @GetMapping("/admin/v1/credentials/expiring")
  public CredentialView.Page expiring(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "30") int days) {
    return service.expiring(operators.resolve(jwt), days);
  }

  @PostMapping("/admin/v1/partners/{partnerId}/credentials/{name}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView upload(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String partnerId, @PathVariable String name,
      @Valid @RequestBody CredentialChangeBody body) {
    return service.request(operators.resolve(jwt), partnerId, name, body);
  }
}
