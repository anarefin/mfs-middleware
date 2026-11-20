package mfs.mw.simulators.service.files;

import com.prowidesoftware.swift.model.field.Field;
import com.prowidesoftware.swift.model.field.Field13D;
import com.prowidesoftware.swift.model.field.Field20;
import com.prowidesoftware.swift.model.field.Field25;
import com.prowidesoftware.swift.model.field.Field28C;
import com.prowidesoftware.swift.model.field.Field34F;
import com.prowidesoftware.swift.model.field.Field60F;
import com.prowidesoftware.swift.model.field.Field61;
import com.prowidesoftware.swift.model.field.Field62F;
import com.prowidesoftware.swift.model.field.Field64;
import com.prowidesoftware.swift.model.field.Field86;
import com.prowidesoftware.swift.model.field.Field90C;
import com.prowidesoftware.swift.model.field.Field90D;
import com.prowidesoftware.swift.model.mt.mt9xx.MT940;
import com.prowidesoftware.swift.model.mt.mt9xx.MT942;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import mfs.mw.simulators.dto.LedgerEntry;

/**
 * The trust bank's statements (ticket 123, FRS EXT-06, MCK-01): a daily MT940 and an intraday MT942 of the trust
 * account, built from ledger entries with Prowide Core's {@code MT940} / {@code MT942} and field classes (never by
 * string concatenation), so the consumer's parser (mw-recon, ticket 126) is exercised for real. Pure and
 * deterministic: the same account, date, opening balance, entries and mismatch give the same bytes.
 *
 * <p>Each entry is one {@code :61:} line (value date, entry date, C/D mark, amount, {@code NTRF}, the last 16
 * characters of the txnId without hyphens as the account owner's reference, {@code TB<n>} as the bank's, the
 * entry kind as supplementary details) followed by {@code :86:} with the whole txnId. The closing balance is the
 * opening balance plus the lines shown, so an injected mismatch keeps the statement self-consistent and only
 * disagrees with the ledger (MCT-05): {@code MISSING_IN_PARTNER} drops the first {@code count} lines, {@code
 * MISSING_IN_UPAY} adds {@code count} credits of {@code n} x 10.00 taka (txn {@code TB-EXTRA-<yyMMdd>-<n>}),
 * {@code AMOUNT_MISMATCH} adds 1.00 taka to the first {@code count} lines, {@code DC_FLIP} flips their mark.
 */
public class TrustStatementGenerator {

  /** The mismatch kinds of {@code INJECTED_MISMATCH} on the trustbank party. */
  public static final Set<String> MISMATCH_KINDS =
      Set.of("MISSING_IN_PARTNER", "MISSING_IN_UPAY", "AMOUNT_MISMATCH", "DC_FLIP");

  /** The bank's business day. */
  public static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

  private static final String CURRENCY = "BDT";
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
  private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

  /** The trust account: the bank's BIC (sender), the middleware's (receiver) and the account number ({@code :25:}). */
  public record TrustAccount(String senderBic, String receiverBic, String accountNumber) {}

  /** An injected mismatch: a kind of {@link #MISMATCH_KINDS} and how many lines it touches. */
  public record Mismatch(String kind, int count) {
    public static final Mismatch NONE = new Mismatch(null, 0);
  }

  private record Line(String txnId, String kind, boolean credit, long amountMinor, LocalDate valueDate) {}

  /** The daily MT940 of {@code date} (Asia/Dhaka), {@code openingMinor} the balance at the start of the day. */
  public String mt940(TrustAccount account, LocalDate date, long openingMinor, List<LedgerEntry> entries, Mismatch mismatch) {
    List<Line> lines = apply(lines(entries), date, mismatch);
    MT940 mt = new MT940(account.senderBic(), account.receiverBic());
    mt.addField(new Field20().setReference("TB940" + YYMMDD.format(date)));
    mt.addField(new Field25().setAccount(account.accountNumber()));
    mt.addField(new Field28C().setStatementNumber(date.getDayOfYear()).setSequenceNumber(1));
    mt.addField(new Field60F().setDCMark(mark(openingMinor >= 0)).setDate(YYMMDD.format(date)).setCurrency(CURRENCY)
        .setAmount(money(Math.abs(openingMinor))));
    long closing = openingMinor;
    int n = 0;
    for (Line l : lines) {
      addLine(mt::addField, l, ++n);
      closing += l.credit() ? l.amountMinor() : -l.amountMinor();
    }
    mt.addField(new Field62F().setDCMark(mark(closing >= 0)).setDate(YYMMDD.format(date)).setCurrency(CURRENCY)
        .setAmount(money(Math.abs(closing))));
    mt.addField(new Field64().setDCMark(mark(closing >= 0)).setDate(YYMMDD.format(date)).setCurrency(CURRENCY)
        .setAmount(money(Math.abs(closing))));
    return mt.message();
  }

  /** The intraday MT942 of {@code date} up to {@code at} (Asia/Dhaka): the day's lines so far and their totals. */
  public String mt942(TrustAccount account, LocalDate date, LocalTime at, List<LedgerEntry> entries, Mismatch mismatch) {
    List<Line> lines = apply(lines(entries), date, mismatch);
    MT942 mt = new MT942(account.senderBic(), account.receiverBic());
    mt.addField(new Field20().setReference("TB942" + YYMMDD.format(date) + HHMM.format(at)));
    mt.addField(new Field25().setAccount(account.accountNumber()));
    mt.addField(new Field28C().setStatementNumber(date.getDayOfYear()).setSequenceNumber(1));
    mt.addField(new Field34F().setCurrency(CURRENCY).setAmount(BigDecimal.ZERO));
    mt.addField(new Field13D().setDate(YYMMDD.format(date)).setTime(HHMM.format(at)).setSign("+").setOffset("0600"));
    long debits = 0;
    long credits = 0;
    int debitCount = 0;
    int creditCount = 0;
    int n = 0;
    for (Line l : lines) {
      addLine(mt::addField, l, ++n);
      if (l.credit()) {
        credits += l.amountMinor();
        creditCount++;
      } else {
        debits += l.amountMinor();
        debitCount++;
      }
    }
    mt.addField(new Field90D().setNumber(debitCount).setCurrency(CURRENCY).setAmount(money(debits)));
    mt.addField(new Field90C().setNumber(creditCount).setCurrency(CURRENCY).setAmount(money(credits)));
    return mt.message();
  }

  private static void addLine(Consumer<Field> sink, Line l, int n) {
    String compact = l.txnId().replace("-", "");
    String ownerRef = compact.length() > 16 ? compact.substring(compact.length() - 16) : compact;
    sink.accept(new Field61()
        .setValueDate(YYMMDD.format(l.valueDate()))
        .setEntryDate(MMDD.format(l.valueDate()))
        .setDebitCreditMark(mark(l.credit()))
        .setAmount(money(l.amountMinor()))
        .setTransactionType("N")
        .setIdentificationCode("TRF")
        .setReferenceForTheAccountOwner(ownerRef)
        .setReferenceOfTheAccountServicingInstitution("TB" + n)
        .setSupplementaryDetails(l.kind()));
    sink.accept(new Field86().setNarrative(l.txnId()));
  }

  private static List<Line> lines(List<LedgerEntry> entries) {
    List<Line> out = new ArrayList<>();
    for (LedgerEntry e : entries) {
      out.add(new Line(e.txnId(), e.kind(), e.credit(), e.amountMinor(), e.at().atZone(DHAKA).toLocalDate()));
    }
    return out;
  }

  private static List<Line> apply(List<Line> lines, LocalDate date, Mismatch m) {
    if (m == null || m.kind() == null || m.count() <= 0) {
      return lines;
    }
    List<Line> out = new ArrayList<>(lines);
    int count = m.count();
    switch (m.kind()) {
      case "MISSING_IN_PARTNER" -> out.subList(0, Math.min(count, out.size())).clear();
      case "MISSING_IN_UPAY" -> {
        for (int i = 1; i <= count; i++) {
          out.add(new Line("TB-EXTRA-" + YYMMDD.format(date) + "-" + i, "ADJUSTMENT", true, i * 1_000L, date));
        }
      }
      case "AMOUNT_MISMATCH" -> {
        for (int i = 0; i < Math.min(count, out.size()); i++) {
          Line l = out.get(i);
          out.set(i, new Line(l.txnId(), l.kind(), l.credit(), l.amountMinor() + 100, l.valueDate()));
        }
      }
      case "DC_FLIP" -> {
        for (int i = 0; i < Math.min(count, out.size()); i++) {
          Line l = out.get(i);
          out.set(i, new Line(l.txnId(), l.kind(), !l.credit(), l.amountMinor(), l.valueDate()));
        }
      }
      default -> throw new IllegalArgumentException("unknown mismatch kind " + m.kind());
    }
    return out;
  }

  private static String mark(boolean credit) {
    return credit ? "C" : "D";
  }

  private static BigDecimal money(long minor) {
    return BigDecimal.valueOf(minor, 2);
  }
}
