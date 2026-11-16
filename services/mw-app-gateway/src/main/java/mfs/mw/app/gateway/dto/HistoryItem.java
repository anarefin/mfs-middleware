package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One transaction of the signed-in wallet's history (contract {@code HistoryItem} of {@code app.v1},
 * ADR D25): {@code direction} {@code OUT} (the wallet paid) or {@code IN} (it was paid);
 * {@code counterparty} is the other side, masked as mw-orchestrator masks it (an MSISDN {@code
 * 0171****789}, an account last 4, a name to the initial). {@code fee} and {@code error} only on an
 * {@code OUT} item: the payer's, not the payee's business.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistoryItem(
    String txnId,
    String txnType,
    String direction,
    String status,
    Amount amount,
    Amount fee,
    Party counterparty,
    String coreRef,
    ErrorInfo error,
    Instant requestedAt,
    Instant updatedAt) {}
