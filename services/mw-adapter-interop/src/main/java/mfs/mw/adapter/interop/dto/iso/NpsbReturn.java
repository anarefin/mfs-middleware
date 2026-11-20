package mfs.mw.adapter.interop.dto.iso;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import mfs.mw.adapter.interop.dto.Money;

/** The canonical side of a pacs.004.001.09 payment return (ticket 121b), one transaction. */
public record NpsbReturn(
    String msgId,
    OffsetDateTime createdAt,
    String returnId,
    String originalMsgId,
    String originalEndToEndId,
    String originalTxId,
    Money returnedAmount,
    LocalDate settlementDate,
    String reasonCode) {}
