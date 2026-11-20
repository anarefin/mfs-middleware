package mfs.mw.recon.dto;

import java.util.Locale;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;

/** DAILY reads the completed runs (MT940, partner files); INTRADAY reads the MT942 runs (FRS REC-05). */
public enum ReportKind {
  DAILY,
  INTRADAY;

  /** {@code daily} or {@code intraday}, any case; anything else is MW-VAL-001. */
  public static ReportKind of(String text) {
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, "kind must be DAILY or INTRADAY", e);
    }
  }
}
