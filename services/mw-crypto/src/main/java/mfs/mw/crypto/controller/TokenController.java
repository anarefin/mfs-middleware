package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.TokeniseRequest;
import mfs.mw.crypto.dto.TokensView;
import mfs.mw.crypto.service.TokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Deterministic tokenisation (crypto-internal.v1, spec 18.8). */
@RestController
public class TokenController {

  private final TokenService tokens;

  public TokenController(TokenService tokens) {
    this.tokens = tokens;
  }

  @PostMapping("/internal/v1/tokens")
  public TokensView tokenise(@Valid @RequestBody TokeniseRequest request) {
    return tokens.tokenise(request);
  }
}
