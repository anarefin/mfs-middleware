package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import mfs.mw.app.gateway.dto.LedgerStatement;
import mfs.mw.app.gateway.dto.WalletLimits;
import mfs.mw.app.gateway.dto.WalletProfile;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.AccountReadService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile, limits and the statement (ticket 107b, CH-APP-08). The wallet is always the token's
 * {@code msisdn}. Session level ({@code X-Device-Id}, a live session), no signature: nothing moves.
 * mw-app-gateway calls the core itself (WAL-01, WAL-05, WAL-12).
 */
@RestController
@Validated
public class AccountReadController {

  private final AccountReadService reads;
  private final AppUserResolver users;

  public AccountReadController(AccountReadService reads, AppUserResolver users) {
    this.reads = reads;
    this.users = users;
  }

  @GetMapping("/app/v1/profile")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public WalletProfile profile(@AuthenticationPrincipal Jwt jwt) {
    return reads.profile(users.resolve(jwt).msisdn());
  }

  @GetMapping("/app/v1/limits")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public WalletLimits limits(@AuthenticationPrincipal Jwt jwt) {
    return reads.limits(users.resolve(jwt).msisdn());
  }

  @GetMapping(value = "/app/v1/statement", produces = {MediaType.APPLICATION_JSON_VALUE, "text/csv"})
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public ResponseEntity<Object> statement(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
      @RequestHeader(name = "Accept", required = false) String accept) {
    LedgerStatement pageBody = reads.statement(users.resolve(jwt).msisdn(), from, to, limit, page);
    if (accept != null && accept.contains("text/csv")) {
      return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(AccountReadService.csv(pageBody));
    }
    return ResponseEntity.ok(pageBody);
  }
}
