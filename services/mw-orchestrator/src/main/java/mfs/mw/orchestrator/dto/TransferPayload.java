package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload of {@code ValidateTransaction} and {@code PostTransfer} (contract {@code
 * core.command.v1}). The core decides the fee, so none is sent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferPayload(
    String txnType, Party debitParty, Party creditParty, Amount amount, EncryptedPin encryptedPin) {}
