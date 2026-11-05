package mfs.mw.notify.dto;

/** A final {@code deliver_sm} delivery receipt, matched on the SMSC {@code message_id}. */
public record DeliveryReport(String providerMessageId, DlrOutcome outcome) {}
