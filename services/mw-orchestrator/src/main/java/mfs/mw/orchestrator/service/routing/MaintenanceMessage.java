package mfs.mw.orchestrator.service.routing;

import java.time.Instant;

/**
 * A partner or biller the routing module treats as in maintenance (ticket 98, FRS PLT-043).
 * {@code validFrom} absent means already open; {@code validUntil} absent means until cleared.
 */
public record MaintenanceMessage(
    String id,
    String partnerId,
    String billerCode,
    String messageEn,
    String messageBn,
    Instant validFrom,
    Instant validUntil,
    boolean enabled,
    int version) {}
