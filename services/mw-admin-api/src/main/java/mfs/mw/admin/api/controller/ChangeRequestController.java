package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import mfs.mw.admin.api.dto.ChangeRequestBody;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.RejectBody;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.ChangeRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maker-checker on transaction actions (ticket 37, spec 15.3; contract {@code admin.v1}). Roles
 * are checked by {@code config/SecurityConfig} and again by the service.
 */
@RestController
@RequestMapping("/admin/v1")
public class ChangeRequestController {

  private final ChangeRequestService service;
  private final OperatorResolver operators;

  public ChangeRequestController(ChangeRequestService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @PostMapping("/transactions/{txnId}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView create(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID txnId, @Valid @RequestBody ChangeRequestBody body) {
    return service.create(operators.resolve(jwt), txnId, body.action(), body.reason());
  }

  @GetMapping("/change-requests")
  public ChangeRequestView.Page list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) ChangeRequest.Status status,
      @RequestParam(required = false) @Size(min = 1, max = 128) String resourceId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return service.list(operators.resolve(jwt), status, resourceId, limit, offset);
  }

  @GetMapping("/change-requests/{id}")
  public ChangeRequestView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(operators.resolve(jwt), id);
  }

  @PostMapping("/change-requests/{id}/approve")
  public ChangeRequestView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.approve(operators.resolve(jwt), id);
  }

  @PostMapping("/change-requests/{id}/reject")
  public ChangeRequestView reject(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RejectBody body) {
    return service.reject(operators.resolve(jwt), id, body.reason());
  }
}
