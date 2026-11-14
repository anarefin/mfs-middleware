package mfs.mw.auth.controller;

import jakarta.validation.Valid;
import mfs.mw.auth.dto.TxnAuthorisation;
import mfs.mw.auth.dto.TxnAuthorisationRequest;
import mfs.mw.auth.service.TxnAuthorisationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The transaction authorisation assertion (spec 18.7, D22; ticket 71; contract {@code
 * auth-internal.v1.openapi.yaml}) on the internal mTLS port; the caller is the services token's
 * {@code azp}.
 */
@RestController
public class TxnAuthorisationController {

  private final TxnAuthorisationService service;

  public TxnAuthorisationController(TxnAuthorisationService service) {
    this.service = service;
  }

  @PostMapping("/internal/v1/txn-authorisations")
  public TxnAuthorisation authorise(
      @AuthenticationPrincipal Jwt caller, @Valid @RequestBody TxnAuthorisationRequest body) {
    return service.authorise(caller == null ? null : caller.getClaimAsString("azp"), body);
  }
}
