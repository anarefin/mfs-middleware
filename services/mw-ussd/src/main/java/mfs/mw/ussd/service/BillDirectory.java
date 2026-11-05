package mfs.mw.ussd.service;

import java.util.List;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;

/**
 * Billers and bill fetch behind the BILL_PAY menu (spec 9.2 DYNAMIC billers). Refusals throw
 * {@code BillPayRefusedException} (the adapter's envelope), outages {@code
 * BillPayUnavailableException} or another runtime exception.
 */
public interface BillDirectory {

  /** The catalogue, sorted by code, so a list number means the same biller within a session. */
  List<Biller> billers();

  Bill fetch(String billerCode, String billNumber);
}
