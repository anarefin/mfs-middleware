package mfs.mw.notify.service;

import java.util.List;

/**
 * A rendered SMS ready for {@code submit_sm}: the alphabet, and one {@code short_message} per
 * part. With more than one part every part starts with the concatenation UDH and {@link #udhi()}
 * is set, so {@code esm_class} must carry the UDHI flag.
 */
public record EncodedSms(SmsEncoding encoding, List<byte[]> segments) {

  public EncodedSms {
    segments = List.copyOf(segments);
  }

  public byte dataCoding() {
    return encoding.dataCoding();
  }

  public boolean udhi() {
    return segments.size() > 1;
  }
}
