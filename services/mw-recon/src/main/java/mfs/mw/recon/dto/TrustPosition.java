package mfs.mw.recon.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REC-07: the sum of all e-money wallet balances against the trust bank closing balance for one business date.
 * {@code differenceMinor} is trust minus e-money; non-zero raises {@code exceptionId}.
 */
public record TrustPosition(LocalDate businessDate, long emoneyTotalMinor, long trustClosingMinor,
    long differenceMinor, UUID exceptionId) {}
