package mfs.mw.adapter.interop.dto.iso;

import java.time.OffsetDateTime;

/**
 * The canonical side of a camt.029.001.09 resolution of investigation (ticket 121b): the answer to
 * our camt.056, {@code confirmation} = ISO {@code Sts/Conf} ({@code CNCL} the return is made, a
 * pacs.004 follows; {@code RJCR} refused).
 */
public record NpsbResolution(
    String assignmentId,
    OffsetDateTime createdAt,
    String resolvedCaseId,
    String confirmation,
    String originalEndToEndId,
    String originalTxId,
    String assigner,
    String assignee) {}
