package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The body of the signed refund IPN (contract {@code checkout.v1.openapi.yaml#/components/schemas/RefundIpn},
 * ticket 116); signed and sent like the payment IPN, {@code X-Bits-Ipn-Id} = the refundId.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
  "type", "refundId", "sessionId", "orderId", "status", "originalTxnId", "amount", "errorCode", "finishedAt"
})
public record RefundIpnPayload(
    String type,
    String refundId,
    String sessionId,
    String orderId,
    String status,
    String originalTxnId,
    Amount amount,
    String errorCode,
    String finishedAt) {

  public static final String REFUND = "REFUND";
}
