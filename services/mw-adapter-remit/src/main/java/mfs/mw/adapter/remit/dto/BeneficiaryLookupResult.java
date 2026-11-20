package mfs.mw.adapter.remit.dto;

/**
 * The answer to a beneficiary lookup (remit-inbound.v1 {@code LookupResult}, ticket 119c, FRS UC-16):
 * the holder's first initial padded with {@code *} to the name's length (null when the core has no
 * name) and whether the wallet can receive a remittance now. Nothing else about the wallet is told.
 */
public record BeneficiaryLookupResult(String maskedName, boolean eligible) {}
