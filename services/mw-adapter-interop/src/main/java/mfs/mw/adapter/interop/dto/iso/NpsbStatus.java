package mfs.mw.adapter.interop.dto.iso;

import java.time.OffsetDateTime;

/**
 * The canonical side of a pacs.002.001.10 status report (ticket 121b), one transaction: {@code status}
 * is the ISO {@code TxSts} (ACSP / ACSC = success, RJCT = definite failure, PDNG = not known yet),
 * {@code reasonCode} the ISO {@code StsRsnInf/Rsn/Cd} of a rejection, {@code clearingRef} the switch's
 * {@code ClrSysRef}.
 */
public record NpsbStatus(
    String msgId,
    OffsetDateTime createdAt,
    String originalMsgId,
    String originalEndToEndId,
    String originalTxId,
    String status,
    String reasonCode,
    String clearingRef) {}
