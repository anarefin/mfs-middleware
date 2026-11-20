package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.RoutingChangeBody;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.RoutingChangeService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Routing rules, prefixes, MNP overrides and maintenance messages (ticket 98, FRS ADM-04). */
@RestController
@Validated
@RequestMapping("/admin/v1")
public class RoutingController {

  private final RoutingChangeService service;
  private final OperatorResolver operators;

  public RoutingController(RoutingChangeService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @PostMapping("/routing-rules/{id}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView proposeRule(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody RoutingChangeBody body) {
    return service.proposeRule(operators.resolve(jwt), id, body);
  }

  @PostMapping("/msisdn-prefixes/{prefix}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView proposePrefix(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String prefix, @Valid @RequestBody RoutingChangeBody body) {
    return service.proposePrefix(operators.resolve(jwt), prefix, body);
  }

  @PostMapping("/mnp-overrides/{msisdn}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView proposeOverride(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String msisdn, @Valid @RequestBody RoutingChangeBody body) {
    return service.proposeOverride(operators.resolve(jwt), msisdn, body);
  }

  @PostMapping("/maintenance-messages/{id}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView proposeMessage(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody RoutingChangeBody body) {
    return service.proposeMessage(operators.resolve(jwt), id, body);
  }
}
