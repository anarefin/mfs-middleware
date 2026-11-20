package mfs.mw.notify.dto;

/**
 * The push server's acknowledgement (ticket 110).
 *
 * @param providerMessageId its id for the push, or null when its answer could not be read
 * @param delivered true when it reports the push delivered to the device, false when only accepted
 */
public record PushResult(String providerMessageId, boolean delivered) {}
