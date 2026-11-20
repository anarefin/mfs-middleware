package mfs.mw.notify.dto;

import java.time.Instant;

/** Filters of {@code GET /internal/v1/notifications} (ticket 111, NOT-08). Blank strings are absent. */
public record NotificationLogQuery(
    String msisdn,
    String txnId,
    String channel,
    String status,
    Instant from,
    Instant to,
    int limit,
    int offset) {}
