package mfs.mw.recon.dto;

import java.time.LocalDate;

/**
 * One line to match: a partner settlement line or a statement line. A blank status is a success
 * claim (the line is on a settlement or statement file). Amounts are poisha.
 */
public record ExternalLine(
    String partnerRef, String reference, long amountMinor, String status, LocalDate valueDate) {}
