package mfs.mw.auth.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.auth.config.TokenProperties;
import mfs.mw.auth.dto.PinKey;
import mfs.mw.auth.service.JwksService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public key documents (spec 18.7; ticket 69): the access tokens' JWK Set and discovery document
 * (read by APISIX's {@code openid-connect} and mw-app-gateway) and the PIN transport key the apps
 * encrypt the PIN with. No token needed.
 */
@RestController
public class WellKnownController {

  private final JwksService jwks;
  private final TokenProperties props;

  public WellKnownController(JwksService jwks, TokenProperties props) {
    this.jwks = jwks;
    this.props = props;
  }

  @GetMapping("/auth/v1/.well-known/jwks.json")
  public ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(props.jwksCacheTtl())).body(jwks.signingJwks());
  }

  @GetMapping("/auth/v1/.well-known/openid-configuration")
  public Map<String, Object> discovery() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("issuer", props.issuer());
    d.put("jwks_uri", props.jwksUri());
    d.put("id_token_signing_alg_values_supported", List.of("RS256"));
    d.put("subject_types_supported", List.of("public"));
    d.put("response_types_supported", List.of("token"));
    d.put("token_endpoint_auth_methods_supported", List.of("none"));
    return d;
  }

  @GetMapping("/auth/v1/pin-key")
  public PinKey pinKey() {
    return jwks.pinKey();
  }
}
