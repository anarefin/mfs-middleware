package mfs.mw.portal.api.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.dto.Party;

/**
 * The SP report (ticket 129d, FRS CH-POR-05): CSV, header {@link #HEADER}, one line per transaction
 * in the order read (newest first). Party ids arrive masked from the orchestrator (18.3) and are
 * written as they came; amounts in taka with two decimals. A cell holding a comma, quote or line
 * break is quoted; a cell a spreadsheet would read as a formula ({@code = + - @}, tab, CR) is
 * prefixed with {@code '} first, so an SP's own reference can never run in the SP's spreadsheet.
 */
public final class SpReportFile {

  public static final String HEADER =
      "txnId,createdAt,txnType,role,status,amount,fee,debitPartyType,debitPartyId,creditPartyType,creditPartyId,"
          + "reference,errorCode";

  private SpReportFile() {}

  public static String of(List<OrgTxn> txns) {
    StringBuilder b = new StringBuilder(160 * (txns.size() + 1)).append(HEADER).append('\n');
    for (OrgTxn t : txns) {
      Map<String, String> attributes = t.attributes() == null ? Map.of() : t.attributes();
      List<String> cells =
          List.of(
              text(t.txnId()),
              t.createdAt() == null ? "" : t.createdAt().toString(),
              text(t.txnType()),
              text(t.role()),
              text(t.status()),
              taka(t.amount()),
              taka(t.fee()),
              t.debitParty() == null ? "" : text(t.debitParty().type()),
              id(t.debitParty()),
              t.creditParty() == null ? "" : text(t.creditParty().type()),
              id(t.creditParty()),
              text(attributes.get("reference")),
              t.error() == null ? "" : text(t.error().code()));
      for (int i = 0; i < cells.size(); i++) {
        if (i > 0) {
          b.append(',');
        }
        b.append(cell(cells.get(i)));
      }
      b.append('\n');
    }
    return b.toString();
  }

  private static String id(Party p) {
    return p == null ? "" : text(p.id());
  }

  private static String text(String s) {
    return s == null ? "" : s;
  }

  private static String taka(Amount a) {
    return a == null ? "" : BigDecimal.valueOf(a.valueMinor(), 2).toPlainString();
  }

  static String cell(String value) {
    String v = value;
    if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !isAmount(v)) {
      v = "'" + v;
    }
    if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
      return '"' + v.replace("\"", "\"\"") + '"';
    }
    return v;
  }

  private static boolean isAmount(String v) {
    return v.matches("-[0-9]+\\.[0-9]{2}");
  }
}
