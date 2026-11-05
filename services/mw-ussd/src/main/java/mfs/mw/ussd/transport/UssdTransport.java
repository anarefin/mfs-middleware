package mfs.mw.ussd.transport;

import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;

/**
 * The transport SPI of spec 9.2: one menu engine, whatever carries the session (SMPP or HTTP).
 * A transport turns its wire events into these calls and delivers the returned screen.
 */
public interface UssdTransport {

  UssdProperties.Transport type();

  UssdResponse onBegin(UssdRequest request);

  UssdResponse onContinue(UssdRequest request);

  void onEnd(UssdRequest request);

  void onAbort(UssdRequest request);

  /** Delivers a screen outside a request/response exchange (network-initiated). */
  void send(UssdResponse response);
}
