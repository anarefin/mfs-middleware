package mfs.mw.adapter.interop.dto.iso;

import java.time.OffsetDateTime;
import mfs.mw.adapter.interop.dto.Money;

/**
 * The canonical side of a camt.056.001.08 return (cancellation) request (ticket 121b): the case
 * {@code assignmentId}, the original transfer and amount, the ISO cancellation reason code
 * ({@code CUST}, {@code DUPL}, ...), assigner (us) and assignee (the creditor's participant).
 */
public record NpsbReturnRequest(
    String assignmentId,
    OffsetDateTime createdAt,
    String originalMsgId,
    String originalEndToEndId,
    String originalTxId,
    Money originalAmount,
    String reasonCode,
    String assigner,
    String assignee) {}
