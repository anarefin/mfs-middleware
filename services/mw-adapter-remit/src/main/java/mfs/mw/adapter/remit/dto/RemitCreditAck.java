package mfs.mw.adapter.remit.dto;

/**
 * The answer to a credit or a status query (remit-inbound.v1): the partner's reference, our Upay
 * reference (the txnId) and the remittance's status; {@code duplicate} on a re-sent credit.
 */
public record RemitCreditAck(String partnerRef, String upayRef, String status, boolean duplicate) {}
