package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One transaction as mw-orchestrator's query API answers a wallet-scoped search (contract {@code
 * txn-query.v1} {@code Txn}, 1.3.0): party ids already masked there, {@code direction} the
 * wallet's side ({@code IN} / {@code OUT}). Only what the history shows is read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryTxn(
    String txnId,
    String txnType,
    String direction,
    String status,
    Instant createdAt,
    Instant updatedAt,
    Amount amount,
    Amount fee,
    Party debitParty,
    Party creditParty,
    String coreRef,
    ErrorInfo error) {}
