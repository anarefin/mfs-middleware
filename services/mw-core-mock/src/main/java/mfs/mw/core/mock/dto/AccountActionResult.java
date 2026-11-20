package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer of an account action (contract {@code AccountActionResult}, ticket 95b): an unlock ({@code
 * UNLOCKED}) or a KYC factors check ({@code VERIFIED}, with the reset's id). Built from the stored result
 * event, so a repeat answers the same.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountActionResult(String txnId, String walletId, String status, String coreRef, String resetId) {}
