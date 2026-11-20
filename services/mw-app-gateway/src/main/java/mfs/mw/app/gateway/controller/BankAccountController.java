package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import mfs.mw.app.gateway.dto.LinkBankAccountRequest;
import mfs.mw.app.gateway.dto.LinkedBankAccount;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.LinkedAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Linked bank accounts (ticket 117b, FRS FL-08): {@code GET|POST /app/v1/bank-accounts}, {@code DELETE
 * /app/v1/bank-accounts/{id}}. The wallet is always the token's {@code msisdn}. Session level ({@code
 * X-Device-Id}, a live session), no signature: nothing moves. Another wallet's row is 404.
 */
@RestController
public class BankAccountController {

  private final LinkedAccountService service;
  private final AppUserResolver users;

  public BankAccountController(LinkedAccountService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @GetMapping("/app/v1/bank-accounts")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public LinkedBankAccount.Page list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(users.resolve(jwt).msisdn());
  }

  @PostMapping("/app/v1/bank-accounts")
  @ResponseStatus(HttpStatus.CREATED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public LinkedBankAccount link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkBankAccountRequest body) {
    return service.link(users.resolve(jwt).msisdn(), body.bankCode(), body.accountNumber());
  }

  @DeleteMapping("/app/v1/bank-accounts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public void unlink(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    service.unlink(users.resolve(jwt).msisdn(), id);
  }
}
