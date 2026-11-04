package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload of {@code CreditWallet} (contract {@code core.command.v1}): inbound money into {@code
 * creditParty}; {@code debitParty} says where it came from (a bank account for ADD_MONEY_BANK). No
 * PIN: the customer is not paying.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditPayload(String txnType, Party debitParty, Party creditParty, Amount amount) {}
