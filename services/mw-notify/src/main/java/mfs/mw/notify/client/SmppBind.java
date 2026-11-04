package mfs.mw.notify.client;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import mfs.mw.notify.config.SmppProperties;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One SMPP transceiver bind (spec 9.2.1): {@code enquire_link} on the configured interval (jSMPP
 * closes the session when the link stops answering), reconnect with exponential back-off from
 * {@code reconnect-min} to {@code reconnect-max}. Bind up and down are logged as {@code
 * smpp.bind.up} / {@code smpp.bind.down} for the alert rule (21.4).
 */
final class SmppBind {

  private static final Logger log = LoggerFactory.getLogger(SmppBind.class);

  private final String name;
  private final SmppProperties props;
  private final MessageReceiverListener receiver;
  private final Clock clock;

  private SMPPSession session;
  private Instant nextReconnect = Instant.EPOCH;
  private Duration backoff;

  SmppBind(String name, SmppProperties props, MessageReceiverListener receiver, Clock clock) {
    this.name = name;
    this.props = props;
    this.receiver = receiver;
    this.clock = clock;
    this.backoff = props.reconnectMin();
  }

  String name() {
    return name;
  }

  /** The bound session, connecting first when it is down and its back-off has passed; else null. */
  synchronized SMPPSession bound() {
    if (session != null && session.getSessionState().isBound()) {
      return session;
    }
    if (session != null) {
      session.close();
      session = null;
    }
    Instant now = clock.instant();
    if (now.isBefore(nextReconnect)) {
      return null;
    }
    SMPPSession s = new SMPPSession();
    s.setEnquireLinkTimer((int) props.enquireLink().toMillis());
    s.setTransactionTimer(props.transactionTimeout().toMillis());
    s.setMessageReceiverListener(receiver);
    s.addSessionStateListener(
        (newState, oldState, src) -> {
          if (oldState.isBound() && !newState.isBound()) {
            log.atWarn().addKeyValue("event", "smpp.bind.down").addKeyValue("bind", name)
                .addKeyValue("state", newState).log("SMPP bind lost");
          }
        });
    try {
      s.connectAndBind(props.host(), props.port(),
          new BindParameter(BindType.BIND_TRX, props.systemId(), props.password(), props.systemType(),
              TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null),
          props.transactionTimeout().toMillis());
      session = s;
      backoff = props.reconnectMin();
      log.atInfo().addKeyValue("event", "smpp.bind.up").addKeyValue("bind", name).log("SMPP bound");
      return s;
    } catch (IOException | RuntimeException e) {
      s.close();
      nextReconnect = now.plus(backoff);
      log.atWarn().addKeyValue("event", "smpp.bind.down").addKeyValue("bind", name)
          .addKeyValue("retryInMs", backoff.toMillis()).addKeyValue("errorClass", e.getClass().getName())
          .log("SMPP bind failed");
      Duration doubled = backoff.multipliedBy(2);
      backoff = doubled.compareTo(props.reconnectMax()) > 0 ? props.reconnectMax() : doubled;
      return null;
    }
  }

  boolean isBound() {
    SMPPSession s = session;
    return s != null && s.getSessionState() == SessionState.BOUND_TRX;
  }

  /** Drops the session after an I/O error, so the next use reconnects. */
  synchronized void reset() {
    if (session != null) {
      session.close();
      session = null;
    }
  }

  synchronized void close() {
    if (session != null) {
      session.unbindAndClose();
      session = null;
    }
  }
}
