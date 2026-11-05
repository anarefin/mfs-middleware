package mfs.mw.ussd.dto;

/** What a transport reports for one USSD step (spec 9.2 transport SPI). */
public enum UssdEvent {
  BEGIN,
  CONTINUE,
  END,
  ABORT
}
