package mfs.mw.auth.controller;

import mfs.mw.auth.dto.InternalPinKey;
import mfs.mw.auth.service.ChannelPinKeyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/v1/pin-key} (security review of ticket 72; contract {@code
 * auth-internal.v1.openapi.yaml}): the PIN transport key for the channels on the internal mTLS port;
 * the caller is the services token's {@code azp}.
 */
@RestController
public class InternalPinKeyController {

  private final ChannelPinKeyService keys;

  public InternalPinKeyController(ChannelPinKeyService keys) {
    this.keys = keys;
  }

  @GetMapping("/internal/v1/pin-key")
  public InternalPinKey pinKey(@AuthenticationPrincipal Jwt caller) {
    return keys.pinKey(caller == null ? null : caller.getClaimAsString("azp"));
  }
}
