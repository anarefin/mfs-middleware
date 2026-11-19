package mfs.mw.core.mock.dto;

import java.time.Instant;
import java.util.List;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * WAL-12 {@code GET /core/v1/statements}: the wallet's entries in [from, to), oldest first, one page
 * of {@code limit} (ticket 94: {@code page} from 0, {@code hasMore} when a next page has entries).
 */
public record StatementView(
    @Sensitive(SensitiveType.MSISDN) String walletId, Instant from, Instant to, int page, boolean hasMore,
    List<EntryView> entries) {}
