package mfs.mw.ussd.client;

import java.io.IOException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.session.SMPPSession;

/**
 * A jSMPP client session whose {@code enquire_link} is driven by {@link UssdSmppBind} (jSMPP's own
 * sender closes the session at the first unanswered one; spec 9.2.1 allows three).
 */
final class UssdSmppSession extends SMPPSession {

  /** Sends one enquire_link and waits for its response (the session's transaction timer). */
  void enquireLinkNow() throws ResponseTimeoutException, InvalidResponseException, IOException {
    sendEnquireLink();
  }
}
