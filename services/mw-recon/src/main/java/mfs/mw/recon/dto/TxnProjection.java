package mfs.mw.recon.dto;

import java.time.LocalDate;

/** mw-recon's own view of one transaction, projected from {@code mw.txn.status.v1}. */
public record TxnProjection(
    String txnId,
    String partnerId,
    String partnerRef,
    String reference,
    long amountMinor,
    String state,
    LocalDate valueDate) {}
