package mfs.mw.ussd.client;

import mfs.mw.ussd.dto.SmppUssdMessage;

/**
 * Receives the USSD {@code deliver_sm} of every SMPP bind (spec 9.2). Called on jSMPP's PDU
 * threads: an implementation hands the work off at once and never blocks the bind.
 */
@FunctionalInterface
public interface UssdSmppListener {

  void onDeliver(SmppUssdMessage message);
}
