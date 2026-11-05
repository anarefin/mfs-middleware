package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.PartnerChangeRequestBody;
import mfs.mw.admin.api.dto.PartnerView;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.PartnerConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner config through maker-checker (ticket 38, spec 15.2 Partners; contract {@code admin.v1}).
 * Approve / reject go through {@code ChangeRequestController}. Roles are checked by {@code
 * config/SecurityConfig} and again by the service.
 */
@RestController
@RequestMapping("/admin/v1/partners")
public class PartnerController {

  private final PartnerConfigService service;
  private final OperatorResolver operators;

  public PartnerController(PartnerConfigService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @GetMapping
  public PartnerView.Page list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(operators.resolve(jwt));
  }

  @GetMapping("/{partnerId}")
  public PartnerView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String partnerId) {
    return service.get(operators.resolve(jwt), partnerId);
  }

  @PostMapping("/{partnerId}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView request(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String partnerId, @Valid @RequestBody PartnerChangeRequestBody body) {
    return service.request(operators.resolve(jwt), partnerId, body);
  }
}
