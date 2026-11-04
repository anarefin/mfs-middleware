package mfs.mw.audit.dw.service;

/**
 * Outcome of checking one monthly hash chain: intact, or the first sequence number at which it
 * breaks and why.
 */
public record ChainVerification(String month, boolean intact, long rows, Long failedSeq, String reason) {

  static ChainVerification ok(String month, long rows) {
    return new ChainVerification(month, true, rows, null, null);
  }

  static ChainVerification broken(String month, long rows, long seq, String reason) {
    return new ChainVerification(month, false, rows, seq, reason);
  }
}
