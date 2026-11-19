package mfs.mw.core.mock.service;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.core.mock.entity.LedgerEntry;

/**
 * The double entries of a payment (ticket 94, WAL-07, WAL-09): the amount from the payer to the
 * credit wallet, and the payer's fee split into FEE_INCOME (kind FEE), VAT_PAYABLE (VAT) and
 * COMMISSION_PAYABLE (COMMISSION). Debits equal credits.
 */
final class Legs {

  private Legs() {}

  static List<LedgerEntry> payment(String payer, String creditWallet, long amount, long fee, long vat, long commission) {
    List<LedgerEntry> legs = new ArrayList<>();
    legs.add(new LedgerEntry(payer, "DEBIT", "TRANSFER", amount));
    legs.add(new LedgerEntry(creditWallet, "CREDIT", "TRANSFER", amount));
    if (fee > 0) {
      legs.add(new LedgerEntry(payer, "DEBIT", "FEE", fee));
      legs.add(new LedgerEntry(WalletSeeder.FEE_INCOME, "CREDIT", "FEE", fee - vat - commission));
      legs.add(new LedgerEntry(WalletSeeder.VAT_PAYABLE, "CREDIT", "VAT", vat));
      legs.add(new LedgerEntry(WalletSeeder.COMMISSION_PAYABLE, "CREDIT", "COMMISSION", commission));
    }
    return legs;
  }

  static List<LedgerEntry> payment(String payer, String creditWallet, long amount, Charges c) {
    return payment(payer, creditWallet, amount, c.fee(), c.vat(), c.commission());
  }
}
