package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of {@code ValidateTransaction} and {@code PostTransfer} (contract {@code
 * core.command.v1}). The fee the orchestrator may send is ignored: the core decides the fee.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferPayload(
    String txnType, Party debitParty, Party creditParty, Amount amount, EncryptedPin encryptedPin) {}
