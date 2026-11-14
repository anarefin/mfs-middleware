package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.CryptoKeys;
import mfs.mw.admin.api.dto.KeyCeremonyBody;
import mfs.mw.admin.api.dto.KeyCeremonyView;
import mfs.mw.admin.api.dto.KeyChangeBody;
import mfs.mw.admin.api.dto.KeyComponentBody;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.KeyAdminService;
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
 * Keys and the key ceremony (ticket 75, contract {@code admin.v1}). Roles are checked by {@code
 * config/SecurityConfig} and again by the service (with the fresh-MFA rule).
 */
@RestController
@RequestMapping("/admin/v1")
public class KeyController {

  private final KeyAdminService keys;
  private final OperatorResolver operators;

  public KeyController(KeyAdminService keys, OperatorResolver operators) {
    this.keys = keys;
    this.operators = operators;
  }

  @GetMapping("/keys")
  public CryptoKeys.Page list(@AuthenticationPrincipal Jwt jwt) {
    return keys.list(operators.resolve(jwt));
  }

  @PostMapping("/keys/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView requestChange(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KeyChangeBody body) {
    return keys.requestChange(operators.resolve(jwt), body);
  }

  @PostMapping("/key-ceremonies")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView startCeremony(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KeyCeremonyBody body) {
    return keys.startCeremony(operators.resolve(jwt), body);
  }

  @GetMapping("/key-ceremonies/{id}")
  public KeyCeremonyView ceremony(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return keys.ceremony(operators.resolve(jwt), id);
  }

  @PostMapping("/key-ceremonies/{id}/components")
  public KeyCeremonyView enterComponent(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody KeyComponentBody body) {
    return keys.enterComponent(operators.resolve(jwt), id, body);
  }
}
