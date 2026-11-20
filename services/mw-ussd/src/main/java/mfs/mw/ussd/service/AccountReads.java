package mfs.mw.ussd.service;

/**
 * Account screens that are not transactions: the mini statement (WAL-12) and a remittance status
 * (WAL-13 by the Upay reference). The text is already in the customer's language.
 */
public interface AccountReads {

  String statement(String walletId, String language);

  String remitStatus(String reference, String language);
}
