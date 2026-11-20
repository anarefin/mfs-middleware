package mfs.mw.recon.dto;

import java.time.LocalDate;

/** One :61: of an MT940 or MT942 (ticket 126). {@code reference} is the :86: narrative, the txn id. */
public record StatementLine(
    int lineNo,
    LocalDate valueDate,
    long amountMinor,
    String dcMark,
    String reference,
    String supplementary) {}
