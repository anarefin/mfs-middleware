package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The fields of mw-recon's {@code ReconException} ({@code api/mw-recon/recon.v1}, ticket 127b) a {@code
 * recon_exception} change request needs: its state and the transaction it is about.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReconExceptionView(
    String id, String reconClass, String detail, String partnerId, String txnId, String state, String action,
    String changeRequestId) {

  /** An action may be asked for and approved only while the exception is still open (OPEN or EXPLAINED). */
  public boolean actionable() {
    return "OPEN".equals(state) || "EXPLAINED".equals(state);
  }
}
