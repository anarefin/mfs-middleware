package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * {@code mw.notify.status.v1} (contract {@code events/mw-notify/mw.notify.status.v1}, owned by
 * this service): one record per attempt and per final status (spec 10.4). Key: messageId.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotifyStatusEvent(
    int schemaVersion,
    String messageId,
    String txnId,
    String correlationId,
    Channel channel,
    NotifyStatus status,
    @JsonProperty("final") boolean isFinal,
    int attempt,
    String templateCode,
    String providerMessageId,
    String errorCode,
    Instant at) {}
