package mfs.mw.adapter.interop.dto.iso;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import mfs.mw.adapter.interop.dto.Money;

/**
 * The canonical side of a pacs.008.001.08 FI-to-FI customer credit transfer (ticket 121b), one
 * transaction per message: NPSB_OUT outbound with our stable reference as {@code EndToEndId}, NPSB_IN
 * inbound deduplicated on the switch's {@code EndToEndId} / {@code TxId} (PLT-014).
 */
public record NpsbTransfer(
    String msgId,
    OffsetDateTime createdAt,
    String endToEndId,
    String txId,
    Money amount,
    LocalDate settlementDate,
    NpsbParty debtor,
    NpsbParty creditor) {}
