package mfs.mw.ussd.service;

/**
 * The fee the core quotes for a transaction (WAL-04 {@code POST /core/v1/charges/calculate}) before
 * the PIN screen. {@code feeMinor} is what the payer pays on top of the amount, VAT included.
 */
public interface ChargeQuote {

  Quoted quote(String txnType, long amountMinor, String customerType);

  record Quoted(long feeMinor, long totalMinor) {}
}
