package mfs.mw.simulators.dto;

import java.time.Instant;
import java.util.Map;

/**
 * An IPN the merchant receiver got (ticket 32), as received, with the status it answered. Test data:
 * the body carries no personal data; the e2e suite verifies the signature itself.
 *
 * @param ipnId the {@code X-Upay-Ipn-Id} header (the checkout sessionId)
 */
public record ReceivedIpn(
    long seq, Instant receivedAt, String path, String ipnId, Map<String, String> headers, String body, int answeredStatus) {}
