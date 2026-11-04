package mfs.mw.notify.dto;

/** The SMSC accepted a part: the bind it went out on and the SMSC {@code message_id}. */
public record SubmitResult(String bind, String providerMessageId) {}
