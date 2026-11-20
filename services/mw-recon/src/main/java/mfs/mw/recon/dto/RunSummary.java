package mfs.mw.recon.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The answer of a pull, an ingest or a reprocess. A skipped file was already registered (REC-08). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunSummary(
    String runId,
    String fileId,
    String partnerId,
    String source,
    String fileName,
    String businessDate,
    int matched,
    int missingInPartner,
    int missingInUpay,
    int amountMismatch,
    int statusMismatch,
    boolean skipped) {

  public static RunSummary skipped(String fileId, String partnerId, String source, String fileName) {
    return new RunSummary(null, fileId, partnerId, source, fileName, null, 0, 0, 0, 0, 0, true);
  }
}
