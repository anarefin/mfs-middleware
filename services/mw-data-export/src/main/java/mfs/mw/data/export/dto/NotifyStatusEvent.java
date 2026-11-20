package mfs.mw.data.export.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** The fields of {@code mw.notify.status.v1} this service projects. The source has no recipient. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifyStatusEvent(
    String messageId,
    String txnId,
    String correlationId,
    String channel,
    String status,
    String templateCode,
    String recipient,
    Instant at) {}
