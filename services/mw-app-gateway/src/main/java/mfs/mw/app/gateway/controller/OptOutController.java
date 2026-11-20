package mfs.mw.app.gateway.controller;

import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.SignedRequests;
import mfs.mw.app.gateway.service.OptOutService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /app/v1/notifications/opt-out} (ticket 111, NOT-09; contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}): the signed-in wallet opts out of promotional messages.
 * No body. The device gate is the session default ({@code X-Device-Id}, a live session). 204 once
 * handed to mw-notify. OTP and transactional messages are not affected.
 */
@RestController
public class OptOutController {

  private final OptOutService service;
  private final AppUserResolver users;

  public OptOutController(OptOutService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @PostMapping("/app/v1/notifications/opt-out")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void optOut(@AuthenticationPrincipal Jwt jwt, @RequestHeader(SignedRequests.DEVICE_ID) String deviceId) {
    service.optOut(users.resolve(jwt), deviceId);
  }
}
