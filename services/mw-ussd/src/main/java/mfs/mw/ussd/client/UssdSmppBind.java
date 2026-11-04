package mfs.mw.ussd.client;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import mfs.mw.ussd.config.UssdSmppProperties;
import mfs.mw.ussd.dto.SmppUssdMessage;
import mfs.mw.ussd.util.UssdText;
import org.jsmpp.GenericNackResponseException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.MessageType;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One USSD transceiver bind to an MNO gateway (spec 9.2, 9.2.1). A keeper (virtual thread) binds,
 * sends {@code enquire_link} every {@code enquire-link} and drops the bind after {@code
 * enquire-link-misses} unanswered ones in a row, and reconnects with exponential back-off from
 * {@code reconnect-min} to {@code reconnect-max}. {@code submit_sm} goes through a window
 * (outstanding requests) and a TPS throttle, and is retried after a delay on {@code
 * generic_nack}, throttling (0x58) and queue full (0x14). Bind up and down are logged as {@code
 * smpp.bind.up} / {@code smpp.bind.down} (21.4) and exposed as {@link #isUp()}.
 */
final class UssdSmppBind {

  private static final Logger log = LoggerFactory.getLogger(UssdSmppBind.class);

  private static final Set<Integer> RETRIED = Set.of(SMPPConstant.STAT_ESME_RTHROTTLED, SMPPConstant.STAT_ESME_RMSGQFUL);
  private static final Duration TICK = Duration.ofMillis(50);

  private final String name;
  private final String mno;
  private final UssdSmppProperties.Endpoint ep;
  private final UssdSmppListener listener;
  private final Clock clock;
  private final Semaphore window;
  private final long slotNanos;

  private volatile UssdSmppSession session;
  private volatile String downReason;
  private volatile boolean running;
  private Thread keeper;
  private Instant nextReconnect = Instant.EPOCH;
  private Instant nextEnquire = Instant.EPOCH;
  private Duration backoff;
  private int misses;
  private long nextSlot;

  UssdSmppBind(String name, String mno, UssdSmppProperties.Endpoint ep, UssdSmppListener listener, Clock clock) {
    this.name = name;
    this.mno = mno;
    this.ep = ep;
    this.listener = listener;
    this.clock = clock;
    this.window = new Semaphore(ep.window(), true);
    this.slotNanos = TimeUnit.SECONDS.toNanos(1) / ep.tps();
    this.backoff = ep.reconnectMin();
  }

  String name() {
    return name;
  }

  String mno() {
    return mno;
  }

  boolean isUp() {
    UssdSmppSession s = session;
    return s != null && s.getSessionState().isBound();
  }

  synchronized void start() {
    running = true;
    keeper = Thread.ofVirtual().name("ussd-smpp-" + name).start(this::keep);
  }

  synchronized void stop() {
    running = false;
    if (keeper != null) {
      keeper.interrupt();
    }
    UssdSmppSession s = session;
    session = null;
    if (s != null) {
      downReason = "stopped";
      if (s.getSessionState().isBound()) {
        s.unbindAndClose();
      } else {
        s.close();
      }
    }
  }

  private void keep() {
    while (running) {
      try {
        tick();
      } catch (RuntimeException e) {
        log.atWarn().addKeyValue("event", "smpp.bind.error").addKeyValue("bind", name)
            .addKeyValue("errorClass", e.getClass().getName()).log("SMPP bind keeper error");
      }
      try {
        Thread.sleep(TICK);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** One keeper step: bind when down (after back-off), enquire_link when due. */
  void tick() {
    UssdSmppSession s = session;
    Instant now = clock.instant();
    if (s == null || !s.getSessionState().isBound()) {
      if (s != null) {
        s.close();
        session = null;
      }
      if (!now.isBefore(nextReconnect)) {
        connect(now);
      }
      return;
    }
    if (now.isBefore(nextEnquire)) {
      return;
    }
    nextEnquire = now.plus(ep.enquireLink());
    try {
      s.enquireLinkNow();
      misses = 0;
    } catch (ResponseTimeoutException e) {
      misses++;
      log.atWarn().addKeyValue("event", "smpp.enquire_link.missed").addKeyValue("bind", name)
          .addKeyValue("misses", misses).log("enquire_link not answered");
      if (misses >= ep.enquireLinkMisses()) {
        drop(s, "enquire_link");
      }
    } catch (InvalidResponseException | IOException e) {
      drop(s, "enquire_link_error");
    }
  }

  private void drop(UssdSmppSession s, String reason) {
    downReason = reason;
    s.close();
    session = null;
    nextReconnect = clock.instant().plus(ep.reconnectMin());
  }

  private void connect(Instant now) {
    UssdSmppSession s = new UssdSmppSession();
    // enquire_link is sent by tick(), so three misses (not one) drop the bind
    s.setEnquireLinkTimer(0);
    s.setTransactionTimer(ep.transactionTimeout().toMillis());
    s.setMessageReceiverListener(new Receiver());
    s.addSessionStateListener(
        (newState, oldState, src) -> {
          if (oldState.isBound() && !newState.isBound()) {
            String reason = downReason == null ? "closed" : downReason;
            downReason = null;
            log.atWarn().addKeyValue("event", "smpp.bind.down").addKeyValue("bind", name).addKeyValue("mno", mno)
                .addKeyValue("reason", reason).log("SMPP USSD bind lost");
          }
        });
    try {
      s.connectAndBind(ep.host(), ep.port(),
          new BindParameter(BindType.BIND_TRX, ep.systemId(), ep.password(), ep.systemType(),
              TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null),
          ep.transactionTimeout().toMillis());
      misses = 0;
      nextEnquire = now.plus(ep.enquireLink());
      backoff = ep.reconnectMin();
      session = s;
      log.atInfo().addKeyValue("event", "smpp.bind.up").addKeyValue("bind", name).addKeyValue("mno", mno)
          .log("SMPP USSD bound");
    } catch (IOException | RuntimeException e) {
      s.close();
      nextReconnect = now.plus(backoff);
      log.atWarn().addKeyValue("event", "smpp.bind.down").addKeyValue("bind", name).addKeyValue("mno", mno)
          .addKeyValue("reason", "bind_failed").addKeyValue("retryInMs", backoff.toMillis())
          .addKeyValue("errorClass", e.getClass().getName()).log("SMPP USSD bind failed");
      Duration doubled = backoff.multipliedBy(2);
      backoff = doubled.compareTo(ep.reconnectMax()) > 0 ? ep.reconnectMax() : doubled;
    }
  }

  /** Sends one screen, retrying after the configured delays when the gateway asks to slow down. */
  void submit(SmppUssdMessage m) throws UssdSmppException {
    List<Duration> delays = ep.retryDelays();
    for (int attempt = 0; ; attempt++) {
      int status;
      try {
        send(m);
        return;
      } catch (Retryable r) {
        status = r.status;
      }
      if (attempt >= delays.size()) {
        throw new UssdSmppException("submit_sm not accepted after " + attempt + " retries", status);
      }
      log.atInfo().addKeyValue("event", "smpp.submit.retry").addKeyValue("bind", name)
          .addKeyValue("commandStatus", "0x" + Integer.toHexString(status)).addKeyValue("attempt", attempt + 1)
          .log("submit_sm retried after a delay");
      try {
        Thread.sleep(delays.get(attempt));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new UssdSmppException("interrupted", status);
      }
    }
  }

  private void send(SmppUssdMessage m) throws Retryable, UssdSmppException {
    UssdSmppSession s = session;
    if (s == null || !s.getSessionState().isBound()) {
      throw new Retryable(-1);
    }
    try {
      if (!window.tryAcquire(ep.transactionTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
        throw new Retryable(-1);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UssdSmppException("interrupted", -1);
    }
    try {
      pace();
      UssdText.Encoded text = UssdText.encode(m.text());
      s.submitShortMessage(ep.serviceType(), TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, m.serviceCode(),
          TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, m.address(), new ESMClass(), (byte) 0, (byte) 0, null,
          null, new RegisteredDelivery(0), (byte) 0, DataCodings.newInstance(text.dataCoding()), (byte) 0,
          text.bytes(),
          new OptionalParameter.OctetString(OptionalParameter.Tag.USSD_SERVICE_OP.code(),
              new byte[] {(byte) m.serviceOp()}),
          new OptionalParameter.OctetString(OptionalParameter.Tag.ITS_SESSION_INFO.code(),
              new byte[] {(byte) m.sessionNumber(), (byte) (((m.sequence() & 0x7F) << 1) | (m.endOfSession() ? 1 : 0))}));
    } catch (NegativeResponseException e) {
      if (RETRIED.contains(e.getCommandStatus())) {
        throw new Retryable(e.getCommandStatus());
      }
      throw new UssdSmppException("submit_sm refused, status 0x" + Integer.toHexString(e.getCommandStatus()),
          e.getCommandStatus());
    } catch (GenericNackResponseException e) {
      throw new Retryable(e.getCommandStatus());
    } catch (ResponseTimeoutException e) {
      // not retried: the screen may have reached the subscriber
      throw new UssdSmppException("submit_sm without a response", -1);
    } catch (InvalidResponseException | PDUException e) {
      throw new UssdSmppException("submit_sm failed: " + e.getClass().getSimpleName(), -1);
    } catch (IOException e) {
      throw new Retryable(-1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UssdSmppException("interrupted", -1);
    } finally {
      window.release();
    }
  }

  /** TPS throttle: one submit per 1/tps second per bind. */
  private void pace() throws InterruptedException {
    long wait;
    synchronized (this) {
      long now = System.nanoTime();
      long slot = Math.max(now, nextSlot);
      nextSlot = slot + slotNanos;
      wait = slot - now;
    }
    if (wait > 0) {
      TimeUnit.NANOSECONDS.sleep(wait);
    }
  }

  /** A refusal worth a delayed retry. */
  private static final class Retryable extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;

    Retryable(int status) {
      super(null, null, false, false);
      this.status = status;
    }
  }

  static byte[] tlv(OptionalParameter[] params, OptionalParameter.Tag tag) {
    if (params == null) {
      return null;
    }
    for (OptionalParameter p : params) {
      if (p.tag == tag.code()) {
        byte[] all = p.serialize();
        return Arrays.copyOfRange(all, 4, all.length);
      }
    }
    return null;
  }

  /** deliver_sm from the gateway: a subscriber's dial-in, answer or release. */
  private final class Receiver implements MessageReceiverListener {

    @Override
    public void onAcceptDeliverSm(DeliverSm sm) throws ProcessRequestException {
      if (MessageType.SMSC_DEL_RECEIPT.containedIn(sm.getEsmClass())) {
        return;
      }
      byte[] op = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.USSD_SERVICE_OP);
      byte[] info = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO);
      if (op == null || op.length < 1 || info == null || info.length < 2) {
        log.atWarn().addKeyValue("event", "smpp.deliver_sm.ignored").addKeyValue("bind", name)
            .log("deliver_sm without ussd_service_op or its_session_info");
        return;
      }
      SmppUssdMessage m = new SmppUssdMessage(mno, name, sm.getSourceAddr(), sm.getDestAddress(), op[0] & 0xFF,
          info[0] & 0xFF, (info[1] & 0xFF) >> 1, (info[1] & 1) == 1,
          UssdText.decode(sm.getDataCoding(), sm.getShortMessage()));
      try {
        listener.onDeliver(m);
      } catch (RuntimeException e) {
        throw new ProcessRequestException("not accepted", SMPPConstant.STAT_ESME_RSYSERR);
      }
    }

    @Override
    public void onAcceptAlertNotification(AlertNotification alertNotification) {
      // not used
    }

    @Override
    public DataSmResult onAcceptDataSm(DataSm dataSm, Session source) throws ProcessRequestException {
      throw new ProcessRequestException("data_sm not supported", SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }
}
