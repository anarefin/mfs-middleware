package mfs.mw.app.gateway.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.dto.Bill;
import mfs.mw.app.gateway.dto.BillerList;
import mfs.mw.app.gateway.dto.FetchBillRequest;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.BillerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Billers and bill fetch (spec 9.1; contract {@code api/mw-app-gateway/app.v1.openapi.yaml}). */
@RestController
@RequestMapping("/app/v1/billers")
public class BillerController {

  private final BillerService service;
  private final AppUserResolver users;

  public BillerController(BillerService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @GetMapping
  public BillerList billers(@AuthenticationPrincipal Jwt jwt) {
    users.resolve(jwt);
    return new BillerList(service.billers());
  }

  @PostMapping("/{code}/fetch-bill")
  public Bill fetch(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Pattern(regexp = TransactionRequest.BILLER_CODE) String code,
      @Valid @RequestBody FetchBillRequest body) {
    users.resolve(jwt);
    return service.fetch(code, body.billNumber());
  }
}
