package mfs.mw.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /checkout/v1/payments/{paymentId}/refunds} (ticket 116, FRS CH-WEB-03; contract
 * {@code checkout.v1.openapi.yaml#/components/schemas/RefundRequest}). {@code amount} left out = the
 * whole payment.
 */
public record RefundRequest(@Valid Amount amount, @Size(max = 140) String reason) {}
