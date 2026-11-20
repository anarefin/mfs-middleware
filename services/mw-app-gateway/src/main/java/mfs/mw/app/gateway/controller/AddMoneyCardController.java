package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import mfs.mw.app.gateway.dto.CardSessionRequest;
import mfs.mw.app.gateway.dto.CardSessionView;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.CardSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Add money from a card (ticket 118b; contract {@code api/mw-app-gateway/app.v1.openapi.yaml}): open the
 * acquirer's hosted page session (signed, PIN), then follow the transaction.
 */
@RestController
@RequestMapping("/app/v1/add-money/card/sessions")
public class AddMoneyCardController {

  private final CardSessionService service;
  private final AppUserResolver users;

  public AddMoneyCardController(CardSessionService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  /** 201 with the hosted page URL, or 202 PROCESSING (no URL yet) when the budget runs out. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @DeviceGate(DeviceGate.Level.SIGNED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Timestamp", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Nonce", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Signature", required = true)
  public ResponseEntity<CardSessionView> start(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(TransactionController.IDEMPOTENCY_KEY)
          @Pattern(regexp = "[\\x21-\\x7E]{1,64}", message = "must be 1 to 64 visible ASCII characters")
          String idempotencyKey,
      @Valid @RequestBody CardSessionRequest body) {
    CardSessionView view = service.start(users.resolve(jwt), idempotencyKey, body);
    URI location = URI.create("/app/v1/add-money/card/sessions/" + view.txnId());
    return ResponseEntity.status(view.open() ? HttpStatus.CREATED : HttpStatus.ACCEPTED).location(location).body(view);
  }

  @GetMapping("/{txnId}")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public TransactionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String txnId) {
    return service.find(users.resolve(jwt), txnId);
  }
}
