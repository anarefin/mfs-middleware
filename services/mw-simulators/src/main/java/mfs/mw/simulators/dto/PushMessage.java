package mfs.mw.simulators.dto;

import java.time.Instant;
import java.util.Map;

/**
 * A push the mock accepted (ticket 110), {@code GET /push/v1/messages?token=}.
 *
 * @param messageId the id the mock answered ({@code push-<n>})
 * @param senderMessageId the sender's own id from the request
 * @param status DELIVERED, or ACCEPTED (PARTIAL_SUCCESS: taken, not yet delivered)
 */
public record PushMessage(
    long seq,
    String messageId,
    String senderMessageId,
    String token,
    String topic,
    String platform,
    String title,
    String body,
    Map<String, String> data,
    String status,
    Instant receivedAt) {}
