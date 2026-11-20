package mfs.mw.checkout.dto;

import java.util.List;

/**
 * {@code GET /checkout/v1/payments/{paymentId}/refunds} (contract {@code RefundList}, ticket 116): a
 * payment's refunds, oldest first, and the sum of the COMPLETED ones.
 */
public record RefundList(List<RefundView> items, Amount refunded) {}
