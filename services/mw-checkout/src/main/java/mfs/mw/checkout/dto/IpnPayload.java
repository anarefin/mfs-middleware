package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The body of the signed IPN (contract {@code checkout.v1.openapi.yaml#/components/schemas/Ipn}).
 * No payer data: the merchant reads the masked payer from {@code GET /checkout/v1/sessions/{id}}.
 *
 * @param finishedAt when the session became PAID or FAILED (ISO-8601 UTC)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"sessionId", "orderId", "status", "txnId", "amount", "errorCode", "finishedAt"})
public record IpnPayload(
    String sessionId,
    String orderId,
    SessionStatus status,
    String txnId,
    Amount amount,
    String errorCode,
    String finishedAt) {}
