package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** One refund as the merchant sees it (contract {@code Refund}, ticket 116). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundView(
    String refundId,
    String paymentId,
    String orderId,
    String status,
    Amount amount,
    String originalTxnId,
    String reason,
    String errorCode,
    Instant createdAt,
    Instant updatedAt) {}
