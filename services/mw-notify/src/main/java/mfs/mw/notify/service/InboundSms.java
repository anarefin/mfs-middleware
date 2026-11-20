package mfs.mw.notify.service;

import java.util.Optional;
import mfs.mw.notify.util.MsisdnHash;

/** A mobile-originated SMS. {@code STOP} (any case, surrounding space) is a promotional opt-out. */
public final class InboundSms {

  private InboundSms() {}

  /** The canonical MSISDN when {@code text} is the STOP keyword and {@code source} is a mobile number. */
  public static Optional<String> stop(String source, String text) {
    if (text == null || !text.trim().equalsIgnoreCase("STOP")) {
      return Optional.empty();
    }
    String msisdn = MsisdnHash.canonical(source);
    return msisdn == null ? Optional.empty() : Optional.of(msisdn);
  }
}
