package mfs.mw.audit.dw.entity;

/**
 * One row of {@code audit.payload} (ticket 130): a hop body of the event it belongs to, masked.
 * {@code ordinal} is its position in the event's payload list (from 1); {@code size} and {@code
 * sha256} are of {@code body}'s UTF-8 bytes (both null without a body) and are part of the event's
 * chained hash. Never leaves the service layer.
 */
public record PayloadRow(
    int ordinal, String direction, String contentType, String body, String payloadRef, Integer size, byte[] sha256) {}
