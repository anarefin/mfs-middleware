package mfs.mw.recon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** The fields of {@code core.event.v1} a ledger projection needs. The payload is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreLedgerEvent(
    String eventType, String eventId, String txnId, String coreRef, Instant occurredAt, String outcome) {}
