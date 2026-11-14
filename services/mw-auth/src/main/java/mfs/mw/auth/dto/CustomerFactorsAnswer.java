package mfs.mw.auth.dto;

/**
 * The core's answer to VerifyCustomerFactors ({@code CustomerFactorsVerified} / {@code
 * CustomerFactorsRejected} on {@code core.wallet.event.v1}, ticket 70), as the reset needs it.
 */
public record CustomerFactorsAnswer(String eventId, String commandId, String resetId, boolean verified, String reasonCode) {}
