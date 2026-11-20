package mfs.mw.adapter.bank.controller;

import jakarta.validation.Valid;
import mfs.mw.adapter.bank.dto.AccountValidationView;
import mfs.mw.adapter.bank.dto.ValidateAccountRequest;
import mfs.mw.adapter.bank.service.AccountValidationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal account validation (AVL, ticket 117b; contract {@code api/mw-adapter-bank/bank.v1.openapi.yaml}),
 * called by mw-app-gateway over the app zone network with a client-credentials JWT ({@code
 * config/SecurityConfig}); not routed by APISIX. A read: no money moves, nothing is stored.
 */
@RestController
@RequestMapping("/bank/v1/accounts")
public class AccountValidationController {

  private final AccountValidationService service;

  public AccountValidationController(AccountValidationService service) {
    this.service = service;
  }

  @PostMapping("/validate")
  public AccountValidationView validate(@Valid @RequestBody ValidateAccountRequest body) {
    return service.validate(body.bankCode(), body.accountNumber());
  }
}
