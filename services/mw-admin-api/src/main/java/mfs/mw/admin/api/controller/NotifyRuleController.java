package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.NotifyRuleChangeBody;
import mfs.mw.admin.api.dto.NotifyRuleList;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.ContentChangeService;
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
 * Notification rules through maker-checker (ticket 133c, FRS NOT-01; contract {@code admin.v1}).
 * Approve and reject are the generic change-request endpoints. Roles are checked by {@code
 * config/SecurityConfig} and again by the service.
 */
@RestController
@RequestMapping("/admin/v1/notify-rules")
public class NotifyRuleController {

  private final ContentChangeService service;
  private final OperatorResolver operators;

  public NotifyRuleController(ContentChangeService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @GetMapping
  public NotifyRuleList list() {
    return service.notifyRules();
  }

  @PostMapping("/{ruleId}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView propose(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String ruleId, @Valid @RequestBody NotifyRuleChangeBody body) {
    return service.proposeNotifyRule(operators.resolve(jwt), ruleId, body);
  }
}
