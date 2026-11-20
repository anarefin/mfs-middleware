package mfs.mw.simulators.dto;

import java.time.Instant;
import java.util.Map;

/**
 * An IPN the merchant receiver got (ticket 32), as received, with the status it answered. Test data:
 * the body carries no personal data; the e2e suite verifies the signature itself.
 *
 * @param ipnId the {@code X-Bits-Ipn-Id} header (the checkout sessionId; a refund IPN's refundId)
 * @param type {@code REFUND} for a refund IPN (its body's {@code type}, ticket 116), else {@code PAYMENT}
 */
public record ReceivedIpn(
    long seq, Instant receivedAt, String path, String ipnId, String type, Map<String, String> headers, String body,
    int answeredStatus) {

  public static final String PAYMENT = "PAYMENT";
  public static final String REFUND = "REFUND";
}
