package mfs.mw.checkout.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import mfs.mw.checkout.dto.RefundList;
import mfs.mw.checkout.dto.RefundRequest;
import mfs.mw.checkout.dto.RefundView;
import mfs.mw.checkout.service.RefundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant refunds (ticket 116, FRS CH-WEB-03), behind the APISIX routes {@code checkout-v1-refunds}
 * (POST, hmac-auth with Digest) and {@code checkout-v1-refunds-get} (GET, hmac-auth); the consumer
 * name arrives in {@code X-Mw-Merchant-Id}.
 */
@RestController
@RequestMapping("/checkout/v1/payments/{paymentId}/refunds")
public class MerchantRefundController {

  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final RefundService refunds;

  public MerchantRefundController(RefundService refunds) {
    this.refunds = refunds;
  }

  @PostMapping
  // checked by security/MerchantReplayInterceptor (ticket 99); declared here for the generated document
  @Parameter(in = ParameterIn.HEADER, name = "Date", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Nonce", required = true)
  // required; checked in RefundService so a missing one is the 400 MW-VAL-001 envelope with its rule
  @Parameter(in = ParameterIn.HEADER, name = IDEMPOTENCY_KEY, required = true)
  public ResponseEntity<RefundView> create(
      @RequestHeader(name = MerchantSessionController.MERCHANT_HEADER, required = false) String merchantId,
      @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
      @PathVariable String paymentId,
      @Valid @RequestBody RefundRequest body) {
    RefundService.Created c = refunds.create(merchantId, paymentId, idempotencyKey, body);
    HttpStatus status =
        !c.isNew() ? HttpStatus.OK : "PENDING".equals(c.refund().status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(c.refund());
  }

  @GetMapping
  public RefundList list(
      @RequestHeader(name = MerchantSessionController.MERCHANT_HEADER, required = false) String merchantId,
      @PathVariable String paymentId) {
    return refunds.list(merchantId, paymentId);
  }
}
