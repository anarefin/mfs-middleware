package mfs.mw.checkout.dto;

import java.util.List;

/**
 * {@code GET /checkout/v1/payments} (FRS CH-WEB-04, ticket 116): one page of the merchant's own
 * sessions created in {@code [from, to)}, newest first; {@code hasMore} when another page follows.
 */
public record PaymentList(List<MerchantSessionView> items, int page, int size, boolean hasMore) {}
