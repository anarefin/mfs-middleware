package mfs.mw.core.mock.dto;

import java.time.Instant;
import java.util.List;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/** WAL-12 {@code GET /core/v1/statements}: the wallet's entries in [from, to), oldest first. */
public record StatementView(
    @Sensitive(SensitiveType.MSISDN) String walletId, Instant from, Instant to, List<EntryView> entries) {}
