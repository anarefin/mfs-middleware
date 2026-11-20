package mfs.mw.recon.service;

import com.prowidesoftware.swift.model.field.Field61;
import com.prowidesoftware.swift.model.field.Field86;
import com.prowidesoftware.swift.model.mt.mt9xx.MT940;
import com.prowidesoftware.swift.model.mt.mt9xx.MT942;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.recon.dto.StatementLine;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import org.springframework.stereotype.Component;

/**
 * Parses a trust-bank MT940 or MT942 with Prowide (ticket 126, FRS REC-01). The layout is the one
 * ticket 123 writes: each {@code :61:} (value date, C/D, amount, supplementary details) is followed
 * by {@code :86:} carrying the txn id. Amounts become poisha.
 */
@Component
public class Mt940Parser {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  public List<StatementLine> parseMt940(String fin) {
    try {
      MT940 mt = MT940.parse(fin);
      return lines(mt.getField61(), mt.getField86());
    } catch (MwException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "MT940 could not be parsed", e);
    }
  }

  public List<StatementLine> parseMt942(String fin) {
    try {
      MT942 mt = MT942.parse(fin);
      return lines(mt.getField61(), mt.getField86());
    } catch (MwException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "MT942 could not be parsed", e);
    }
  }

  private static List<StatementLine> lines(List<Field61> entries, List<Field86> narratives) {
    if (entries == null) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "statement has no transaction fields");
    }
    List<StatementLine> out = new ArrayList<>();
    int n = 0;
    for (Field61 field : entries) {
      String narrative = narratives != null && n < narratives.size() ? text(narratives.get(n).getValue()) : "";
      String reference = narrative.isBlank() ? text(field.getReferenceForTheAccountOwner()) : narrative;
      out.add(new StatementLine(++n, valueDate(field), minor(field.getAmountAsBigDecimal()), mark(field),
          reference, text(field.getSupplementaryDetails())));
    }
    return List.copyOf(out);
  }

  private static LocalDate valueDate(Field61 field) {
    String raw = field.getValueDate();
    if (raw == null || raw.isBlank()) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "statement line has no value date");
    }
    return LocalDate.parse(raw.trim(), YYMMDD);
  }

  private static long minor(BigDecimal amount) {
    if (amount == null) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "statement line has no amount");
    }
    return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static String mark(Field61 field) {
    String mark = field.getDebitCreditMark();
    if (mark == null || mark.isBlank()) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "statement line has no debit or credit mark");
    }
    String trimmed = mark.trim().toUpperCase();
    if (trimmed.endsWith("C")) {
      return "C";
    }
    if (trimmed.endsWith("D")) {
      return "D";
    }
    throw new MwException(ErrorCodes.VAL_MALFORMED, "statement line has no debit or credit mark");
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }
}
