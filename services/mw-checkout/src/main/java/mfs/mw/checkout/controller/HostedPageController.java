package mfs.mw.checkout.controller;

import jakarta.validation.Valid;
import java.net.URI;
import mfs.mw.checkout.dto.ConfirmRequest;
import mfs.mw.checkout.dto.OtpRequest;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.PageView;
import mfs.mw.checkout.dto.PayResult;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.service.CheckoutPayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosted page API {@code /checkout/pay/v1/{token}} (spec 9.3 step 2), behind the APISIX route
 * {@code checkout-pay}: no login, the pay token from the checkout URL is the bearer.
 */
@RestController
@RequestMapping("/checkout/pay/v1/{token}")
public class HostedPageController {

  private final CheckoutPayService pay;

  public HostedPageController(CheckoutPayService pay) {
    this.pay = pay;
  }

  @GetMapping
  public PageView page(@PathVariable String token) {
    return pay.page(token);
  }

  @PostMapping("/otp")
  public ResponseEntity<OtpSent> otp(@PathVariable String token, @Valid @RequestBody OtpRequest body) {
    return ResponseEntity.accepted().body(pay.sendOtp(token, body.wallet()));
  }

  @PostMapping("/confirm")
  public ResponseEntity<PayResult> confirm(@PathVariable String token, @Valid @RequestBody ConfirmRequest body) {
    PayResult r = pay.confirm(token, body);
    return ResponseEntity.status(r.status() == SessionStatus.PAYING ? HttpStatus.ACCEPTED : HttpStatus.OK).body(r);
  }

  @PostMapping("/cancel")
  public PayResult cancel(@PathVariable String token) {
    return pay.cancel(token);
  }

  /** The browser's way back: 303 to the merchant URL with the signed result. */
  @GetMapping("/return")
  public ResponseEntity<Void> back(@PathVariable String token) {
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(pay.returnUrl(token))).build();
  }
}
