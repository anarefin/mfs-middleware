package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of {@code CreditWallet} (contract {@code core.command.v1}): inbound money, no PIN. The
 * fee the orchestrator may send is ignored; the money comes from the settlement wallet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditPayload(String txnType, Party creditParty, Amount amount) {}
