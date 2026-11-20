package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ChannelClientChangeBody;
import mfs.mw.admin.api.dto.ChannelClientView;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.ChannelClientService;
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
 * The channel client registry through maker-checker (ticket 100a; contract {@code admin.v1}).
 * Approve / reject go through {@code ChangeRequestController}; roles are checked by the service.
 */
@RestController
@RequestMapping("/admin/v1/channel-clients")
public class ChannelClientController {

  private final ChannelClientService service;
  private final OperatorResolver operators;

  public ChannelClientController(ChannelClientService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @GetMapping
  public ChannelClientView.Page list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(operators.resolve(jwt));
  }

  @GetMapping("/{clientId}")
  public ChannelClientView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String clientId) {
    return service.get(operators.resolve(jwt), clientId);
  }

  @PostMapping("/{clientId}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView request(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String clientId, @Valid @RequestBody ChannelClientChangeBody body) {
    return service.request(operators.resolve(jwt), clientId, body);
  }
}
