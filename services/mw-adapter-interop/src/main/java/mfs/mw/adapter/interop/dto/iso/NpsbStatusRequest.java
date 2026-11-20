package mfs.mw.adapter.interop.dto.iso;

import java.time.OffsetDateTime;

/** The canonical side of a pacs.028.001.03 status request (ticket 121b): which transfer to look up. */
public record NpsbStatusRequest(
    String msgId,
    OffsetDateTime createdAt,
    String originalMsgId,
    String originalEndToEndId,
    String originalTxId,
    String instructingAgent) {}
