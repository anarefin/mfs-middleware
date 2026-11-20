package mfs.mw.recon.dto;

import java.util.Locale;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;

/** Report download formats (FRS REC-06). */
public enum ReportFormat {
  CSV("text/csv", "csv"),
  XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
  PDF("application/pdf", "pdf");

  private final String mediaType;
  private final String extension;

  ReportFormat(String mediaType, String extension) {
    this.mediaType = mediaType;
    this.extension = extension;
  }

  public String mediaType() {
    return mediaType;
  }

  public String extension() {
    return extension;
  }

  /** {@code csv}, {@code xlsx} or {@code pdf}, any case; anything else is MW-VAL-001. */
  public static ReportFormat of(String text) {
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, "format must be csv, xlsx or pdf", e);
    }
  }
}
