package mfs.mw.notify.entity;

/** A row of {@code notify.segment}: one SMS part, its SMSC message_id and DLR once known. */
public record SegmentRecord(String messageId, int seq, String providerMessageId, String dlrStatus) {}
