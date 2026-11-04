package mfs.mw.notify.service;

/** SMS alphabets the middleware sends (spec 10.2) and their SMPP {@code data_coding} values. */
public enum SmsEncoding {
  /** GSM 03.38 default alphabet, one septet per byte (SMSC default alphabet). */
  GSM7((byte) 0x00, 160, 153, 1),
  /** UCS-2 (UTF-16BE), for Bangla and any text outside GSM-7. */
  UCS2((byte) 0x08, 70, 67, 2);

  private final byte dataCoding;
  private final int singleLimit;
  private final int partLimit;
  private final int bytesPerUnit;

  SmsEncoding(byte dataCoding, int singleLimit, int partLimit, int bytesPerUnit) {
    this.dataCoding = dataCoding;
    this.singleLimit = singleLimit;
    this.partLimit = partLimit;
    this.bytesPerUnit = bytesPerUnit;
  }

  public byte dataCoding() {
    return dataCoding;
  }

  /** Units (septets or UTF-16 code units) in a message that fits one SMS. */
  public int singleLimit() {
    return singleLimit;
  }

  /** Units per part once the message needs a concatenation UDH. */
  public int partLimit() {
    return partLimit;
  }

  /** Bytes per unit in {@code short_message}. */
  public int bytesPerUnit() {
    return bytesPerUnit;
  }
}
