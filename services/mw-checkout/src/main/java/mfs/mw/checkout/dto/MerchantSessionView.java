package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * {@code GET /checkout/v1/sessions/{id}}: the merchant's view of its session. The payer's number
 * is already masked ({@code 0171****789}, 18.3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantSessionView(
    String sessionId,
    String orderId,
    SessionStatus status,
    Amount amount,
    String txnId,
    String payer,
    String errorCode,
    Instant createdAt,
    Instant expiresAt,
    Instant updatedAt) {}
