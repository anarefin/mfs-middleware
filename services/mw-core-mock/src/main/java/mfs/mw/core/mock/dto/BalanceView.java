package mfs.mw.core.mock.dto;

import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/** WAL-03 {@code GET /core/v1/balance/{walletId}}: available = total - held (contract {@code Balance}). */
public record BalanceView(@Sensitive(SensitiveType.MSISDN) String walletId, Amount available, Amount held, Amount total) {}
