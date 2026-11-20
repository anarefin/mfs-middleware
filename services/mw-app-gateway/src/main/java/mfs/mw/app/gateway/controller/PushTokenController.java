package mfs.mw.app.gateway.controller;

import jakarta.validation.Valid;
import mfs.mw.app.gateway.dto.PushTokenRequest;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.SignedRequests;
import mfs.mw.app.gateway.service.PushTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /app/v1/notifications/push-token} (ticket 110, FRS EXT-04; contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}): the app registers its push device token for the token's wallet
 * and the bound device of the request (session level: bound device with a live session; nothing moves, so
 * no signature). 204 once handed to mw-notify.
 */
@RestController
public class PushTokenController {

  private final PushTokenService service;
  private final AppUserResolver users;

  public PushTokenController(PushTokenService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @PutMapping("/app/v1/notifications/push-token")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(SignedRequests.DEVICE_ID) String deviceId,
      @Valid @RequestBody PushTokenRequest body) {
    service.register(users.resolve(jwt), deviceId, body);
  }
}
