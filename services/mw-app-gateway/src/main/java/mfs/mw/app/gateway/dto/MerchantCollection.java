package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One payment a merchant collected (ticket 30, contract {@code Collection} of {@code app.v1}).
 * {@code payer} is already masked ({@code 0171****789}): the merchant never sees the customer's
 * full number.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantCollection(
    String txnId,
    String status,
    Amount amount,
    String payer,
    String coreRef,
    Instant requestedAt,
    Instant updatedAt) {}
