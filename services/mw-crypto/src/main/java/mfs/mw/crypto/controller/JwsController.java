package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import mfs.mw.crypto.dto.JwsSignRequest;
import mfs.mw.crypto.dto.JwsSignResponse;
import mfs.mw.crypto.service.JwsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** JWS sign and the JWK Set per purpose (crypto-internal.v1, spec 18.8). */
@RestController
@Validated
public class JwsController {

  private final JwsService jws;

  public JwsController(JwsService jws) {
    this.jws = jws;
  }

  @PostMapping("/internal/v1/jws/sign")
  public JwsSignResponse sign(@Valid @RequestBody JwsSignRequest request) {
    return jws.sign(request);
  }

  @GetMapping("/internal/v1/jwks/{purpose}")
  public Map<String, Object> jwks(@PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose) {
    return jws.jwks(purpose);
  }
}
