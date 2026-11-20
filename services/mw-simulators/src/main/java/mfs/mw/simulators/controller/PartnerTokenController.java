package mfs.mw.simulators.controller;

import java.time.Instant;
import java.util.Map;
import mfs.mw.simulators.client.PartnerTokenClient;
import mfs.mw.simulators.config.PartnerTokenProperties;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulator's OAuth2 client-credentials token (ticket 100b) and the edge route's ping.
 * {@code GET /sim/partner-token} returns a partners-realm access token (private_key_jwt, no client
 * secret). {@code GET /sim/oauth2-cc/ping} is what APISIX route {@code test-oauth2-cc} calls after
 * the bearer check: 204, no body. The token is the response and is never logged.
 */
@RestController
public class PartnerTokenController {

  private final PartnerTokenClient tokens;
  private final PartnerTokenProperties props;

  public PartnerTokenController(PartnerTokenClient tokens, PartnerTokenProperties props) {
    this.tokens = tokens;
    this.props = props;
  }

  @GetMapping("/sim/oauth2-cc/ping")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void ping() {}

  @GetMapping("/sim/partner-token")
  Map<String, String> token() {
    if (props.privateKey() == null || props.privateKey().isBlank()) {
      throw new MwException(ErrorCodes.SYS_INTERNAL, "partner token client has no key")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    return Map.of("accessToken", tokens.accessToken(props.tokenUri(), props.clientId(), props.privateKey(), Instant.now()));
  }
}
