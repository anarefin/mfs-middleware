package mfs.mw.audit.dw.service;

/** Outcome of checking one daily anchor (ticket 74): intact, or the first reason it fails. */
public record AnchorVerification(String date, boolean intact, String reason) {

  static AnchorVerification ok(String date) {
    return new AnchorVerification(date, true, null);
  }

  static AnchorVerification broken(String date, String reason) {
    return new AnchorVerification(date, false, reason);
  }
}
