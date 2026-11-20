package mfs.mw.core.mock.dto;

import java.time.Instant;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * WAL-03 {@code GET /core/v1/balance/{walletId}}: available = total - held (contract {@code Balance}).
 * {@code asOf}: when the core read it (a customer's balance inquiry: when its BalanceInquiry was first
 * answered, ticket 95a).
 */
public record BalanceView(
    @Sensitive(SensitiveType.MSISDN) String walletId, Amount available, Amount held, Amount total, Instant asOf) {}
